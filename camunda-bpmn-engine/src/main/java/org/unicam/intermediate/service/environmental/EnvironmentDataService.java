// src/main/java/org/unicam/intermediate/service/environmental/EnvironmentService.java
package org.unicam.intermediate.service.environmental;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.Deployment;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.environmental.LocationArea;
import org.unicam.intermediate.models.pojo.EnvironmentData;
import org.unicam.intermediate.models.pojo.Place;
import org.unicam.intermediate.models.pojo.LogicalPlace;
import org.unicam.intermediate.models.pojo.View;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@Getter
@Scope("singleton")
public class EnvironmentDataService {

    private final RepositoryService repositoryService;
    private final ObjectMapper objectMapper;

    // Hold the data directly in the service
    private EnvironmentData data = new EnvironmentData();

    public EnvironmentDataService(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void initialize() {
        loadEnvironmentData();
    }

    public void loadEnvironmentData() {
        List<Deployment> deployments = repositoryService
                .createDeploymentQuery()
                .orderByDeploymentTime().desc()
                .list();

        for (Deployment deployment : deployments) {
            List<String> resources = repositoryService.getDeploymentResourceNames(deployment.getId());
            for (String res : resources) {
                if ("environment.json".equals(res)) {
                    try (InputStream is = repositoryService.getResourceAsStream(deployment.getId(), res)) {
                        this.data = objectMapper.readValue(is, EnvironmentData.class);
                        log.info("[EnvironmentService] Environment loaded from deployment '{}' with {} places, {} edges, {} logical places",
                                deployment.getName(),
                                data.getPlaces() != null ? data.getPlaces().size() : 0,
                                data.getEdges() != null ? data.getEdges().size() : 0,
                                data.getLogicalPlaces() != null ? data.getLogicalPlaces().size() : 0);
                        return;
                    } catch (IOException e) {
                        log.error("[EnvironmentService] Failed to read environment.json from deployment '{}': {}",
                                deployment.getName(), e.getMessage(), e);
                    }
                }
            }
        }

        // Initialize with empty data if nothing found
        this.data = new EnvironmentData();
        this.data.setPlaces(List.of());
        this.data.setEdges(List.of());
        this.data.setLogicalPlaces(List.of());
        this.data.setViews(List.of());

        log.warn("[EnvironmentService] No environment.json found in any deployment, initialized with empty data");
    }

    // Convenience methods for accessing data

    public List<Place> getPlaces() {
        return data != null && data.getPlaces() != null ? data.getPlaces() : List.of();
    }

    public List<LogicalPlace> getLogicalPlaces() {
        return data != null && data.getLogicalPlaces() != null ? data.getLogicalPlaces() : List.of();
    }

    public List<View> getViews() {
        return data != null && data.getViews() != null ? data.getViews() : List.of();
    }

    public Optional<Place> findPlaceById(String placeId) {
        if (placeId == null || data == null || data.getPlaces() == null || data.getPlaces().isEmpty()) {
            return Optional.empty();
        }
        return data.getPlaces().stream()
                .filter(p -> placeId.equals(p.getId()))
                .findFirst();
    }

    public Optional<Place> findPlaceContainingLocation(double lat, double lon) {
        if (data == null || data.getPlaces() == null) {
            return Optional.empty();
        }
        return data.getPlaces().stream()
                .filter(place -> place.getLocationArea() != null &&
                        place.getLocationArea().contains(lat, lon))
                .findFirst();
    }

    public boolean isLocationInPlace(double lat, double lon, String placeId) {
        return findPlaceById(placeId)
                .map(place -> place.getLocationArea() != null &&
                        place.getLocationArea().contains(lat, lon))
                .orElse(false);
    }

    // Method to refresh environment (can be called from controllers/delegates)
    public void refresh() {
        log.info("[EnvironmentService] Manual refresh triggered");
        loadEnvironmentData();
    }

    public boolean isLoaded() {
        return data != null && data.getPlaces() != null && !data.getPlaces().isEmpty();
    }

    public void reloadEnvironment() {
        loadEnvironmentData();
        log.info("[EnvironmentDataService] Environment data reloaded");
    }
}