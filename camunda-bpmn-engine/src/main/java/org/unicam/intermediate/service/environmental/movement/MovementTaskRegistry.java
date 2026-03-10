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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MovementTaskRegistry {

    private final EnvironmentDataService environmentDataService;
    private final RuntimeService runtimeService;

    // Registry: participantId -> MovementTaskInfo
    private final Map<String, MovementTaskInfo> activeMovementTasks = new ConcurrentHashMap<>();

    public MovementTaskRegistry(EnvironmentDataService environmentDataService,
                                RuntimeService runtimeService) {
        this.environmentDataService = environmentDataService;
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

        MovementTaskInfo taskInfo = new MovementTaskInfo(executionId, destination, participantId);
        activeMovementTasks.put(participantId, taskInfo);
        
        log.info("[MovementRegistry] Registered movement task | Participant: {} | Destination: {} | ExecutionId: {}",
                participantId, destination, executionId);
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
                    environmentDataService.getParticipant(participantId);

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

        Optional<LogicalPlace> logicalDestination = environmentDataService.getLogicalPlaces().stream()
                .filter(lp -> destination.equals(lp.getId()))
                .findFirst();

        // Physical destination: keep current behavior unchanged
        if (logicalDestination.isEmpty()) {
            return destination.equals(currentPosition);
        }

        // Logical destination: complete when current physical place is one of the matched places
        List<String> matchingPhysicalPlaces = resolvePhysicalPlacesForLogicalPlace(logicalDestination.get())
                .stream()
                .map(PhysicalPlace::getId)
                .toList();

        return matchingPhysicalPlaces.contains(currentPosition);
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
        Map<String, Object> attributes = physicalPlace.getAttributes();
        if (attributes == null) {
            return false;
        }

        for (Condition condition : conditions) {
            if (condition == null || condition.getAttribute() == null || condition.getOperator() == null) {
                return false;
            }

            Object actualValue = attributes.get(condition.getAttribute());
            if (!compare(actualValue, condition.getOperator(), condition.getValue())) {
                return false;
            }
        }

        return true;
    }

    private boolean compare(Object actualValue, String operator, Object expectedValue) {
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
