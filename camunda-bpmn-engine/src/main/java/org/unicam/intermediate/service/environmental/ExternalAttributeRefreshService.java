package org.unicam.intermediate.service.environmental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.pojo.PhysicalPlace;
import org.unicam.intermediate.models.environmental.LocationArea;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalAttributeRefreshService {

    private static final String RAIN_ATTR = "rain";
    private static final long CACHE_DURATION_MILLIS = 5 * 60 * 1000; // 5 minutes

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // placeId -> RainCache
    private final Map<String, RainCache> rainCache = new ConcurrentHashMap<>();

    // attributeKey(lowercase) -> refresher function
    private final Map<String, AttributeRefresher> refreshers = new ConcurrentHashMap<>();

    /**
     * Cache entry for rain data: coordinates + timestamp + value
     */
    private static class RainCache {
        final double latitude;
        final double longitude;
        long lastFetchTime;
        boolean isRaining;

        RainCache(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.lastFetchTime = 0;
            this.isRaining = false;
        }

        boolean isCacheValid() {
            return System.currentTimeMillis() - lastFetchTime < CACHE_DURATION_MILLIS;
        }
    }

    @PostConstruct
    void init() {
        registerRefresher(RAIN_ATTR, this::refreshIsRaining);
    }

    /**
     * Refreshes selected attributes from external providers before read.
     */
    public void refreshAttributeIfNeeded(PhysicalPlace place, String key) {
        if (place == null || key == null || key.isBlank()) {
            return;
        }

        AttributeRefresher refresher = refreshers.get(normalizeKey(key));
        if (refresher != null) {
            refresher.refresh(place);
        }
    }

    public void registerRefresher(String attributeKey, AttributeRefresher refresher) {
        if (attributeKey == null || attributeKey.isBlank() || refresher == null) {
            return;
        }
        refreshers.put(normalizeKey(attributeKey), refresher);
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase();
    }

    private void refreshIsRaining(PhysicalPlace place) {
        if (place == null || place.getLocationArea() == null) {
            log.debug("[Environment External Refresh] Place '{}' has no location area, skipping rain refresh", 
                place != null ? place.getId() : "unknown");
            return;
        }

        LocationArea area = place.getLocationArea();
        
        // Calculate centroid of bounding box
        double centerLat = (area.getMinY() + area.getMaxY()) / 2.0;
        double centerLon = (area.getMinX() + area.getMaxX()) / 2.0;

        // Check cache
        RainCache cached = rainCache.computeIfAbsent(
            place.getId(),
            k -> new RainCache(centerLat, centerLon)
        );

        // If cache is still valid, use cached value
        if (cached.isCacheValid()) {
            if (place.getAttributes() != null) {
                place.getAttributes().put(RAIN_ATTR, cached.isRaining);
                log.debug("[Environment External Refresh] Rain (cached) for place '{}' at ({}, {}) = {}",
                    place.getId(), centerLat, centerLon, cached.isRaining);
            }
            return;
        }

        // Fetch fresh data from Open-Meteo
        try {
            String endpoint = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f&current=rain",
                centerLat, centerLon
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[Environment External Refresh] Open-Meteo request failed for place '{}' ({}, {}) with status {}",
                    place.getId(), centerLat, centerLon, response.statusCode());
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode rainNode = root.path("current").path("rain");

            if (rainNode.isMissingNode() || rainNode.isNull()) {
                log.warn("[Environment External Refresh] Missing 'current.rain' in weather response for place '{}' ({}, {})",
                    place.getId(), centerLat, centerLon);
                return;
            }

            boolean isRaining = rainNode.isNumber() && rainNode.asDouble() > 0.0d;
            
            // Update cache
            cached.isRaining = isRaining;
            cached.lastFetchTime = System.currentTimeMillis();

            // Update place attributes
            if (place.getAttributes() != null) {
                place.getAttributes().put(RAIN_ATTR, isRaining);
                log.info("[Environment External Refresh] Updated rain for place '{}' at ({}, {}) = {} (from Open-Meteo)",
                    place.getId(), centerLat, centerLon, isRaining);
            }

        } catch (Exception ex) {
            log.warn("[Environment External Refresh] Failed to refresh '{}' for place '{}' from Open-Meteo: {}",
                    RAIN_ATTR, place.getId(), ex.getMessage());
        }
    }

    @FunctionalInterface
    public interface AttributeRefresher {
        void refresh(PhysicalPlace place);
    }

}
