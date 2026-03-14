package org.unicam.intermediate.service.environmental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.events.ParticipantPositionChangedEvent;
import org.unicam.intermediate.models.pojo.PhysicalPlace;
import org.unicam.intermediate.service.participant.ParticipantDataService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalAttributeRefreshService {

    private static final String IS_RAINING_ATTR = "isRaining";
    private static final String OCCUPIED_ATTR = "occupied";
    private static final String RAIN_ENDPOINT = "https://api.open-meteo.com/v1/forecast?latitude=43.1376&longitude=13.0746&current=rain";

    private final ObjectMapper objectMapper;
    private final ParticipantDataService participantDataService;

    // Lazily injected to break the circular: EnvironmentDataService -> this -> EnvironmentDataService
    private EnvironmentDataService environmentDataService;

    @Autowired
    public void setEnvironmentDataService(@Lazy EnvironmentDataService environmentDataService) {
        this.environmentDataService = environmentDataService;
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // attributeKey(lowercase) -> refresher function
    private final Map<String, AttributeRefresher> refreshers = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        registerRefresher(IS_RAINING_ATTR, this::refreshIsRaining);
        registerRefresher(OCCUPIED_ATTR, this::refreshOccupied);
    }

    @EventListener
    public void onParticipantPositionChanged(ParticipantPositionChangedEvent event) {
        if (environmentDataService == null) {
            return;
        }
        if (event.oldPosition() != null) {
            environmentDataService.getPhysicalPlace(event.oldPosition()).ifPresent(this::refreshOccupied);
        }
        if (event.newPosition() != null) {
            environmentDataService.getPhysicalPlace(event.newPosition()).ifPresent(this::refreshOccupied);
        }
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
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RAIN_ENDPOINT))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[Environment External Refresh] Open-Meteo request failed with status {}", response.statusCode());
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode rainNode = root.path("current").path("rain");

            if (rainNode.isMissingNode() || rainNode.isNull() || !rainNode.isNumber()) {
                log.warn("[Environment External Refresh] Missing or invalid 'current.rain' in weather response");
                return;
            }

            boolean isRaining = rainNode.asDouble() > 0.0d;
            if (place.getAttributes() != null) {
                place.getAttributes().put(IS_RAINING_ATTR, isRaining);
                log.debug("[Environment External Refresh] Updated {} for place '{}' to {}",
                        IS_RAINING_ATTR,
                        place.getId(),
                        isRaining);
            }

        } catch (Exception ex) {
            log.warn("[Environment External Refresh] Failed to refresh '{}' from Open-Meteo: {}",
                    IS_RAINING_ATTR,
                    ex.getMessage());
        }
    }

    private void refreshOccupied(PhysicalPlace place) {
        if (place == null || place.getAttributes() == null) {
            return;
        }
        boolean occupied = participantDataService.getParticipants().stream()
                .anyMatch(p -> place.getId().equals(p.getPosition()));
        place.getAttributes().put(OCCUPIED_ATTR, occupied);
        log.debug("[Environment External Refresh] Updated {} for place '{}' to {}", OCCUPIED_ATTR, place.getId(), occupied);
    }

    @FunctionalInterface
    public interface AttributeRefresher {
        void refresh(PhysicalPlace place);
    }

}
