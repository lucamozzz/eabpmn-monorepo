package org.unicam.intermediate.service.environmental.movement;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.pojo.Condition;
import org.unicam.intermediate.models.pojo.LogicalPlace;
import org.unicam.intermediate.models.pojo.PhysicalPlace;
import org.unicam.intermediate.models.record.MovementTaskInfo;
import org.unicam.intermediate.service.environmental.EnvironmentDataService;
import org.unicam.intermediate.service.participant.ParticipantDataService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MovementTaskRegistry {

    private final EnvironmentDataService environmentDataService;
    private final ParticipantDataService participantDataService;
    private final RuntimeService runtimeService;

    // Registry: participantId -> MovementTaskInfo
    private final Map<String, MovementTaskInfo> activeMovementTasks = new ConcurrentHashMap<>();

    public MovementTaskRegistry(EnvironmentDataService environmentDataService,
                                ParticipantDataService participantDataService,
                                RuntimeService runtimeService) {
        this.environmentDataService = environmentDataService;
        this.participantDataService = participantDataService;
        this.runtimeService = runtimeService;
    }

    /**
     * Registra un nuovo movement task per un participant
     */
    public void registerTask(String participantId, String executionId, String destination) {
        if (participantId == null || executionId == null || destination == null) {
            log.warn("[MovementRegistry] Cannot register task with null values: participantId={}, executionId={}, destination={}",
                    participantId, executionId, destination);
            return;
        }

        String normalizedDestination = resolveDestinationReference(destination);
        MovementTaskInfo taskInfo = new MovementTaskInfo(executionId, normalizedDestination, participantId);
        activeMovementTasks.put(participantId, taskInfo);
        
        log.info("[MovementRegistry] Registered movement task | Participant: {} | Destination: {} | ExecutionId: {}",
            participantId, normalizedDestination, executionId);
    }

    /**
     * Rimuovi un task dal registro
     */
    public void removeTask(String participantId) {
        MovementTaskInfo removed = activeMovementTasks.remove(participantId);
        if (removed != null) {
            log.info("[MovementRegistry] Removed movement task for participant: {}", participantId);
        }
    }

    /**
     * Returns pending movement tasks for a participant in a shape compatible
     * with the mobile pending-actions view.
     */
    public List<Map<String, String>> getPendingMovementsForParticipant(String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return List.of();
        }

        MovementTaskInfo taskInfo = activeMovementTasks.get(participantId);
        if (taskInfo == null) {
            return List.of();
        }

        String currentPosition = participantDataService.getParticipant(participantId)
                .map(p -> p.getPosition())
                .orElse(null);

        // If already reached, no pending movement notification should be shown.
        if (isDestinationReached(taskInfo.destination(), currentPosition)) {
            return List.of();
        }

        String destinationLabel = resolveDestinationLabel(taskInfo.destination());

        Map<String, String> movementView = new LinkedHashMap<>();
        movementView.put("executionId", taskInfo.executionId());
        movementView.put("action", "move");
        movementView.put("destination", taskInfo.destination());
        movementView.put("message", "Move to " + destinationLabel);

        return List.of(movementView);
    }

    private String resolveDestinationLabel(String destination) {
        if (destination == null || destination.isBlank()) {
            return "destination";
        }

        String resolvedDestination = resolveDestinationReference(destination);

        Optional<PhysicalPlace> physicalPlace = environmentDataService.getPhysicalPlace(resolvedDestination);
        if (physicalPlace.isPresent()) {
            String name = physicalPlace.get().getName();
            return name != null && !name.isBlank() ? name : physicalPlace.get().getId();
        }

        Optional<LogicalPlace> logicalPlace = environmentDataService.getLogicalPlaces().stream()
                .filter(lp -> resolvedDestination.equals(lp.getId()))
                .findFirst();

        if (logicalPlace.isPresent()) {
            String name = logicalPlace.get().getName();
            return name != null && !name.isBlank() ? name : logicalPlace.get().getId();
        }

        return destination;
    }

    /**
     * Resolves the list of physical place IDs matched by a logical destination id.
     * Returns an empty list when destination is not a logical place or when no physical places match.
     */
    public List<String> resolveMatchingPhysicalPlaceIdsForLogicalDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return List.of();
        }

        String resolvedDestination = environmentDataService.resolveLogicalPlaceId(destination)
                .orElse(destination);

        Optional<LogicalPlace> logicalDestination = environmentDataService.getLogicalPlaces().stream()
                .filter(lp -> resolvedDestination.equals(lp.getId()))
                .findFirst();

        if (logicalDestination.isEmpty()) {
            return List.of();
        }

        return resolvePhysicalPlacesForLogicalPlace(logicalDestination.get()).stream()
                .map(PhysicalPlace::getId)
                .toList();
    }

    /**
     * Controllo periodico: verifica se i participant hanno raggiunto le loro destinazioni
     * Eseguito ogni 2 secondi
     */
    @Scheduled(fixedRate = 2000)
    public void checkMovementCompletion() {
        if (activeMovementTasks.isEmpty()) {
            return;
        }

        log.debug("[MovementRegistry] Checking {} active movement tasks", activeMovementTasks.size());

        List<String> completedParticipants = new ArrayList<>();

        for (Map.Entry<String, MovementTaskInfo> entry : activeMovementTasks.entrySet()) {
            String participantId = entry.getKey();
            MovementTaskInfo taskInfo = entry.getValue();

            // Ottieni la posizione corrente del participant
            Optional<org.unicam.intermediate.models.pojo.Participant> participantOpt = 
                    participantDataService.getParticipant(participantId);

            if (participantOpt.isPresent()) {
                String currentPosition = participantOpt.get().getPosition();

                // Verifica se ha raggiunto la destinazione
                if (isDestinationReached(taskInfo.destination(), currentPosition)) {
                    log.info("[MovementRegistry] ✓ Participant {} reached destination {} | Completing task",
                            participantId, taskInfo.destination());

                    // Completa il task
                    try {
                        runtimeService.signal(taskInfo.executionId());
                        completedParticipants.add(participantId);
                        log.info("[MovementRegistry] Task completed successfully for participant {}", participantId);
                    } catch (Exception e) {
                        log.error("[MovementRegistry] Failed to signal execution {} for participant {}: {}",
                                taskInfo.executionId(), participantId, e.getMessage(), e);
                    }
                } else {
                    log.trace("[MovementRegistry] Participant {} at position {}, waiting for {}",
                            participantId, currentPosition, taskInfo.destination());
                }
            } else {
                log.warn("[MovementRegistry] Participant {} not found in environment data", participantId);
            }
        }

        // Rimuovi i task completati dal registro
        completedParticipants.forEach(this::removeTask);
    }

    /**
     * A destination can be:
     * 1) a physical place id (legacy behavior), or
     * 2) a logical place id, in which case completion happens when participant
     *    enters any physical place satisfying all logical-place conditions (AND semantics).
     */
    private boolean isDestinationReached(String destination, String currentPosition) {
        if (destination == null || destination.isBlank() || currentPosition == null || currentPosition.isBlank()) {
            return false;
        }

        String resolvedDestination = resolveDestinationReference(destination);
        String resolvedCurrentPosition = environmentDataService.resolvePhysicalPlaceId(currentPosition)
                .orElse(currentPosition);

        Optional<LogicalPlace> logicalDestination = environmentDataService.getLogicalPlaces().stream()
                .filter(lp -> resolvedDestination.equals(lp.getId()))
                .findFirst();

        // Physical destination: keep current behavior unchanged
        if (logicalDestination.isEmpty()) {
            return resolvedDestination.equals(resolvedCurrentPosition);
        }

        // Logical destination: complete when current physical place is one of the matched places
        List<String> matchingPhysicalPlaces = resolveMatchingPhysicalPlaceIdsForLogicalDestination(resolvedDestination);

        return matchingPhysicalPlaces.contains(resolvedCurrentPosition);
    }

    private String resolveDestinationReference(String destination) {
        if (destination == null || destination.isBlank()) {
            return destination;
        }

        return environmentDataService.resolvePhysicalPlaceId(destination)
                .or(() -> environmentDataService.resolveLogicalPlaceId(destination))
                .orElse(destination);
    }

    private List<PhysicalPlace> resolvePhysicalPlacesForLogicalPlace(LogicalPlace logicalPlace) {
        List<Condition> conditions = logicalPlace.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }

        return environmentDataService.getPhysicalPlaces().stream()
                .filter(physicalPlace -> matchesAllConditions(physicalPlace, conditions))
                .toList();
    }

    private boolean matchesAllConditions(PhysicalPlace physicalPlace, List<Condition> conditions) {
        for (Condition condition : conditions) {
            if (condition == null || condition.getAttribute() == null || condition.getOperator() == null) {
                return false;
            }

            Object actualValue = environmentDataService
                    .getPhysicalPlaceAttribute(physicalPlace.getId(), condition.getAttribute())
                    .orElse(null);
            if (!compare(actualValue, condition.getOperator(), condition.getValue())) {
                return false;
            }
        }

        return true;
    }

    private boolean compare(Object actualValue, String operator, Object expectedValue) {
        if (expectedValue == null) {
            return switch (operator) {
                case "==" -> actualValue == null;
                case "!=" -> actualValue != null;
                default -> false;
            };
        }

        if (actualValue == null) {
            return false;
        }

        String actualText = String.valueOf(actualValue).trim();
        String expectedText = expectedValue == null ? "null" : String.valueOf(expectedValue).trim();

        Double actualNumber = toNumber(actualText);
        Double expectedNumber = toNumber(expectedText);

        if (actualNumber != null && expectedNumber != null) {
            return switch (operator) {
                case "==" -> Double.compare(actualNumber, expectedNumber) == 0;
                case "!=" -> Double.compare(actualNumber, expectedNumber) != 0;
                case ">" -> actualNumber > expectedNumber;
                case "<" -> actualNumber < expectedNumber;
                case ">=" -> actualNumber >= expectedNumber;
                case "<=" -> actualNumber <= expectedNumber;
                default -> false;
            };
        }

        return switch (operator) {
            case "==" -> actualText.equalsIgnoreCase(expectedText);
            case "!=" -> !actualText.equalsIgnoreCase(expectedText);
            default -> false;
        };
    }

    private Double toNumber(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
