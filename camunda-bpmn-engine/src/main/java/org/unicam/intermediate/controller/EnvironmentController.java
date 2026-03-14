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
import org.unicam.intermediate.models.pojo.Participant;
import org.unicam.intermediate.service.environmental.EnvironmentDataService;
import org.unicam.intermediate.service.participant.ParticipantDataService;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/environment")
@RequiredArgsConstructor
@Slf4j
public class EnvironmentController {

    private final EnvironmentDataService environmentDataService;
    private final ParticipantDataService participantDataService;

    @GetMapping("/pp")
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

    @GetMapping("/pp/{id}")
    public ResponseEntity<Response<PhysicalPlace>> getPhysicalPlaceById(@PathVariable String id) {
        try {
            Optional<PhysicalPlace> place = environmentDataService.getPhysicalPlace(id);
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

    @GetMapping("/pp/{id}/attributes/{key}")
    public ResponseEntity<Response<Object>> getAttributeByKey(@PathVariable String id, @PathVariable String key) {
        try {
            Optional<PhysicalPlace> place = environmentDataService.getPhysicalPlace(id);
            if (place.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Physical place not found: " + id));
            }

            Optional<Object> attributeValue = environmentDataService.getPhysicalPlaceAttribute(id, key);
            if (attributeValue.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Attribute not found: " + key));
            }
            return ResponseEntity.ok(Response.ok(attributeValue.get()));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve attribute '{}' for place: {}", key, id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve attribute: " + e.getMessage()));
        }
    }

    @PutMapping("/pps/{id}/attributes/{key}")
    public ResponseEntity<Response<Object>> setAttributeByKey(@PathVariable String id, @PathVariable String key,
                                                               @RequestBody Map<String, Object> requestBody) {
        try {
            Optional<PhysicalPlace> place = environmentDataService.getPhysicalPlace(id);
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

    @GetMapping("/participants")
    public ResponseEntity<Response<List<Participant>>> getParticipants() {
        try {
            List<Participant> participants = participantDataService.getParticipants();
            return ResponseEntity.ok(Response.ok(participants));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve participants", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve participants: " + e.getMessage()));
        }
    }

    @GetMapping("/participants/{id}/position")
    public ResponseEntity<Response<String>> getParticipantPosition(@PathVariable String id) {
        try {
            Optional<Participant> participant = participantDataService.getParticipant(id);
            if (participant.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Participant not found: " + id));
            }
            String position = participant.get().getPosition();
            return ResponseEntity.ok(Response.ok(position));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve position for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve position: " + e.getMessage()));
        }
    }

    @PutMapping("/participants/{id}/position")
    public ResponseEntity<Response<String>> setParticipantPosition(@PathVariable String id,
                                                                   @RequestBody Map<String, String> requestBody) {
        try {
            Optional<Participant> participant = participantDataService.getParticipant(id);
            if (participant.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Participant not found: " + id));
            }
            String newPosition = requestBody.get("position");
            if (newPosition == null || newPosition.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Response.error("Position cannot be empty"));
            }
            participantDataService.updateParticipantPosition(id, newPosition);
            log.info("[Environment API] Participant '{}' position set to: {}", id, newPosition);
            return ResponseEntity.ok(Response.ok(newPosition));
        } catch (Exception e) {
            log.error("[Environment API] Failed to set position for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to set position: " + e.getMessage()));
        }
    }

    /**
     * Updates a participant's position by resolving GPS coordinates to a physical place.
     * Body: {"latitude": 43.1376, "longitude": 13.0746}
     */
    @PutMapping("/participants/{id}/position/coordinates")
    public ResponseEntity<Response<String>> setParticipantPositionByCoordinates(
            @PathVariable String id,
            @RequestBody Map<String, Double> requestBody) {
        try {
            Optional<Participant> participant = participantDataService.getParticipant(id);
            if (participant.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Participant not found: " + id));
            }

            Double latitude = requestBody.get("latitude");
            Double longitude = requestBody.get("longitude");
            if (latitude == null || longitude == null) {
                return ResponseEntity.badRequest()
                        .body(Response.error("Request body must contain 'latitude' and 'longitude'"));
            }

            Optional<PhysicalPlace> resolvedPlace = environmentDataService.resolvePhysicalPlaceByCoordinates(latitude, longitude);

            String placeId = resolvedPlace.map(PhysicalPlace::getId).orElse(null);
            participantDataService.updateParticipantPosition(id, placeId);

            if (placeId == null) {
                log.info("[Environment API] Participant '{}' coordinates ({}, {}) did not match any place — position set to null",
                        id, latitude, longitude);
            } else {
                log.info("[Environment API] Participant '{}' position resolved from ({}, {}) to place '{}'",
                        id, latitude, longitude, placeId);
            }
            return ResponseEntity.ok(Response.ok(placeId));
        } catch (Exception e) {
            log.error("[Environment API] Failed to resolve coordinates for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to resolve coordinates: " + e.getMessage()));
        }
    }
}
