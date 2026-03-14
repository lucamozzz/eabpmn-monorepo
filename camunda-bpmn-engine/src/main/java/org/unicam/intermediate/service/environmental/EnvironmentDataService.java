// src/main/java/org/unicam/intermediate/service/environmental/EnvironmentService.java
package org.unicam.intermediate.service.environmental;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.Deployment;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.pojo.EnvironmentData;
import org.unicam.intermediate.models.pojo.Edge;
import org.unicam.intermediate.models.pojo.PhysicalPlace;
import org.unicam.intermediate.models.pojo.LogicalPlace;
import org.unicam.intermediate.models.pojo.View;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@Getter
@Scope("singleton")
public class EnvironmentDataService {

    private final RepositoryService repositoryService;
    private final ObjectMapper objectMapper;
    private final ExternalAttributeRefreshService externalAttributeRefreshService;

    // Hold the data directly in the service
    private EnvironmentData data = new EnvironmentData();

    public EnvironmentDataService(RepositoryService repositoryService,
                                  ExternalAttributeRefreshService externalAttributeRefreshService) {
        this.repositoryService = repositoryService;
        this.objectMapper = new ObjectMapper();
        this.externalAttributeRefreshService = externalAttributeRefreshService;
    }

    @PostConstruct
    public void initialize() {
        loadEnvironmentData();
    }

    // Convenience methods for accessing data

    public List<PhysicalPlace> getPhysicalPlaces() {
        return data != null && data.getPhysicalPlaces() != null ? data.getPhysicalPlaces() : List.of();
    }

    public Optional<PhysicalPlace> getPhysicalPlace(String id) {
        if (id == null || data == null || data.getPhysicalPlaces() == null || data.getPhysicalPlaces().isEmpty()) {
            return Optional.empty();
        }
        return data.getPhysicalPlaces().stream()
                .filter(p -> id.equals(p.getId()))
                .findFirst();
    }

    /**
     * Returns the first physical place whose polygon contains the given GPS coordinates.
     * Uses the existing {@link org.unicam.intermediate.models.environmental.LocationArea#contains(double, double)}
     * winding-number algorithm. Places without coordinates are skipped.
     */
    public Optional<PhysicalPlace> resolvePhysicalPlaceByCoordinates(double latitude, double longitude) {
        return getPhysicalPlaces().stream()
                .filter(p -> p.getLocationArea() != null)
                .filter(p -> p.getLocationArea().contains(latitude, longitude))
                .findFirst();
    }

    /**
     * Reads an attribute from the model after refreshing dynamic attributes when configured.
     * The model remains the source of truth: this method refreshes first, then returns from the model map.
     */
    public Optional<Object> getPhysicalPlaceAttribute(String placeReference, String key) {
        if (placeReference == null || placeReference.isBlank() || key == null || key.isBlank()) {
            return Optional.empty();
        }

        Optional<PhysicalPlace> placeOpt = resolvePhysicalPlace(placeReference);
        if (placeOpt.isEmpty()) {
            return Optional.empty();
        }

        PhysicalPlace place = placeOpt.get();
        externalAttributeRefreshService.refreshAttributeIfNeeded(place, key);

        Map<String, Object> attributes = place.getAttributes();
        if (attributes == null || !attributes.containsKey(key)) {
            return Optional.empty();
        }

        return Optional.ofNullable(attributes.get(key));
    }

    private Optional<PhysicalPlace> resolvePhysicalPlace(String placeReference) {
        return getPhysicalPlace(placeReference)
                .or(() -> resolvePhysicalPlaceId(placeReference).flatMap(this::getPhysicalPlace));
    }

    public List<Edge> getEdges() {
        return data != null && data.getEdges() != null ? data.getEdges() : List.of();
    }

    public List<LogicalPlace> getLogicalPlaces() {
        return data != null && data.getLogicalPlaces() != null ? data.getLogicalPlaces() : List.of();
    }

    public List<View> getViews() {
        return data != null && data.getViews() != null ? data.getViews() : List.of();
    }

    /**
     * Checks if there is at least one directed path from source physical place to target physical place,
     * traversing the configured edges.
     */
    public boolean existsPathBetweenPhysicalPlaces(String sourcePlaceId, String targetPlaceId) {
        if (sourcePlaceId == null || sourcePlaceId.isBlank() || targetPlaceId == null || targetPlaceId.isBlank()) {
            return false;
        }

        if (sourcePlaceId.equals(targetPlaceId)) {
            return true;
        }

        Set<String> physicalPlaceIds = getPhysicalPlaces().stream()
                .map(PhysicalPlace::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        // Validate source/target are known physical places.
        if (!physicalPlaceIds.contains(sourcePlaceId) || !physicalPlaceIds.contains(targetPlaceId)) {
            return false;
        }

        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Edge edge : getEdges()) {
            if (edge == null || edge.getSource() == null || edge.getTarget() == null) {
                continue;
            }

            String from = edge.getSource();
            String to = edge.getTarget();

            // Only consider edges between known physical places.
            if (!physicalPlaceIds.contains(from) || !physicalPlaceIds.contains(to)) {
                continue;
            }

            adjacency.computeIfAbsent(from, ignored -> new HashSet<>()).add(to);
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(sourcePlaceId);
        visited.add(sourcePlaceId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> neighbors = adjacency.getOrDefault(current, Set.of());

            for (String neighbor : neighbors) {
                if (targetPlaceId.equals(neighbor)) {
                    return true;
                }
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        return false;
    }

    public Optional<PhysicalPlace> findPhysicalPlaceContainingLocation(double lat, double lon) {
        if (data == null || data.getPhysicalPlaces() == null) {
            return Optional.empty();
        }
        return data.getPhysicalPlaces().stream()
                .filter(place -> place.getLocationArea() != null &&
                        place.getLocationArea().contains(lat, lon))
                .findFirst();
    }

    public boolean isLocationInPhysicalPlace(double lat, double lon, String placeId) {
        return getPhysicalPlace(placeId)
                .map(place -> place.getLocationArea() != null &&
                        place.getLocationArea().contains(lat, lon))
                .orElse(false);
    }

    /**
     * Resolves a reference (id or name) to the canonical physical place id.
     */
    public Optional<String> resolvePhysicalPlaceId(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        return getPhysicalPlaces().stream()
                .filter(p -> reference.equals(p.getId()) || reference.equalsIgnoreCase(p.getName()))
                .map(PhysicalPlace::getId)
                .findFirst();
    }

    /**
     * Resolves a reference (id or name) to the canonical logical place id.
     */
    public Optional<String> resolveLogicalPlaceId(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        return getLogicalPlaces().stream()
                .filter(p -> reference.equals(p.getId()) || reference.equalsIgnoreCase(p.getName()))
                .map(LogicalPlace::getId)
                .findFirst();
    }

    public boolean isLoaded() {
        return data != null && data.getPhysicalPlaces() != null && !data.getPhysicalPlaces().isEmpty();
    }

    public void reloadEnvironment() {
        loadEnvironmentData();
        log.info("[EnvironmentDataService] Environment data reloaded");
    }

    // // Method to refresh environment (can be called from controllers/delegates)
    // public void refresh() {
    //     log.info("[EnvironmentService] Manual refresh triggered");
    //     loadEnvironmentData();
    // }

    public void loadEnvironmentData() {
        loadEnvironmentFromFile();
        // List<Deployment> deployments = repositoryService
        //         .createDeploymentQuery()
        //         .orderByDeploymentTime().desc()
        //         .list();

        // for (Deployment deployment : deployments) {
        //     List<String> resources = repositoryService.getDeploymentResourceNames(deployment.getId());
        //     for (String res : resources) {
        //         if ("environment.json".equals(res)) {
        //             try (InputStream is = repositoryService.getResourceAsStream(deployment.getId(), res)) {
        //                 this.data = objectMapper.readValue(is, EnvironmentData.class);
        //                 log.info("[EnvironmentService] Environment loaded from deployment '{}' with {} places, {} edges, {} logical places",
        //                         deployment.getName(),
        //                         data.getPlaces() != null ? data.getPlaces().size() : 0,
        //                         data.getEdges() != null ? data.getEdges().size() : 0,
        //                         data.getLogicalPlaces() != null ? data.getLogicalPlaces().size() : 0);
        //                 return;
        //             } catch (IOException e) {
        //                 log.error("[EnvironmentService] Failed to read environment.json from deployment '{}': {}",
        //                         deployment.getName(), e.getMessage(), e);
        //             }
        //         }
        //     }
        // }

        // // Initialize with empty data if nothing found
        // this.data = new EnvironmentData();
        // this.data.setPlaces(List.of());
        // this.data.setEdges(List.of());
        // this.data.setLogicalPlaces(List.of());
        // this.data.setViews(List.of());

        // log.warn("[EnvironmentService] No environment.json found in any deployment, initialized with empty data");
    }

    /**
     * Loads environment.json from the local filesystem (src/main/resources/environment.json)
     * This is an alternative to deployments, useful for development/testing
     */
    public void loadEnvironmentFromFile() {
        try {
            // Try to load from classpath first (standard location in packaged app)
            InputStream is = this.getClass().getClassLoader().getResourceAsStream("environment.json");
            
            if (is != null) {
                this.data = objectMapper.readValue(is, EnvironmentData.class);
                log.info("[EnvironmentDataService] Environment loaded from classpath resource with {} places, {} logical places",
                    data.getPhysicalPlaces() != null ? data.getPhysicalPlaces().size() : 0,
                        data.getLogicalPlaces() != null ? data.getLogicalPlaces().size() : 0);
                return;
            }

            // Fallback: try to load from project directory (for development)
            Path filePath = Paths.get("camunda-bpmn-engine/src/main/resources/environment.json");
            if (Files.exists(filePath)) {
                byte[] fileBytes = Files.readAllBytes(filePath);
                this.data = objectMapper.readValue(fileBytes, EnvironmentData.class);
                log.info("[EnvironmentDataService] Environment loaded from file '{}' with {} places, {} logical places",
                        filePath,
                    data.getPhysicalPlaces() != null ? data.getPhysicalPlaces().size() : 0,
                        data.getLogicalPlaces() != null ? data.getLogicalPlaces().size() : 0);
                return;
            }

            // If no file found, initialize with empty data
            this.data = new EnvironmentData();
            this.data.setPhysicalPlaces(List.of());
            this.data.setEdges(List.of());
            this.data.setLogicalPlaces(List.of());
            this.data.setViews(List.of());

            log.warn("[EnvironmentDataService] No local environment.json file found, initialized with empty data");

        } catch (IOException e) {
            log.error("[EnvironmentDataService] Failed to load environment from file: {}", e.getMessage(), e);
            
            // Fallback: empty data
            this.data = new EnvironmentData();
            this.data.setPhysicalPlaces(List.of());
            this.data.setEdges(List.of());
            this.data.setLogicalPlaces(List.of());
            this.data.setViews(List.of());
        }
    }
}