package org.unicam.intermediate.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unicam.intermediate.models.dto.Response;
import org.unicam.intermediate.models.pojo.PhysicalPlace;
import org.unicam.intermediate.service.environmental.EnvironmentDataService;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/environment")
@RequiredArgsConstructor
@Slf4j
public class EnvironmentController {

    private final EnvironmentDataService environmentDataService;

    @GetMapping("/physical-places")
    public ResponseEntity<Response<List<PhysicalPlace>>> getPhysicalPlaces() {
        try {
            List<PhysicalPlace> physicalPlaces = environmentDataService.getPhysicalPlaces();
            return ResponseEntity.ok(Response.ok(physicalPlaces));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve physical places", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve physical places: " + e.getMessage()));
        }
    }

    @GetMapping("/physical-places/{id}")
    public ResponseEntity<Response<PhysicalPlace>> getPhysicalPlaceById(@PathVariable String id) {
        try {
            Optional<PhysicalPlace> place = environmentDataService.findPhysicalPlaceById(id);
            if (place.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Physical place not found: " + id));
            }
            return ResponseEntity.ok(Response.ok(place.get()));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve physical place with id: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve physical place: " + e.getMessage()));
        }
    }

    @GetMapping("/physical-places/{id}/attributes/{key}")
    public ResponseEntity<Response<Object>> getAttributeByKey(@PathVariable String id, @PathVariable String key) {
        try {
            Optional<PhysicalPlace> place = environmentDataService.findPhysicalPlaceById(id);
            if (place.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Physical place not found: " + id));
            }
            Object attributeValue = place.get().getAttributes().get(key);
            if (attributeValue == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Attribute not found: " + key));
            }
            return ResponseEntity.ok(Response.ok(attributeValue));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve attribute '{}' for place: {}", key, id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve attribute: " + e.getMessage()));
        }
    }

    @PutMapping("/physical-places/{id}/attributes/{key}")
    public ResponseEntity<Response<Object>> setAttributeByKey(@PathVariable String id, @PathVariable String key,
                                                               @RequestBody Map<String, Object> requestBody) {
        try {
            Optional<PhysicalPlace> place = environmentDataService.findPhysicalPlaceById(id);
            if (place.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Physical place not found: " + id));
            }
            Object newValue = requestBody.get("value");
            place.get().getAttributes().put(key, newValue);
            log.info("[Environment API] Attribute '{}' for place '{}' set to: {}", key, id, newValue);
            return ResponseEntity.ok(Response.ok(newValue));
        } catch (Exception e) {
            log.error("[Environment API] Failed to set attribute '{}' for place: {}", key, id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to set attribute: " + e.getMessage()));
        }
    }
}
