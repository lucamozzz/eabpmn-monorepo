package org.unicam.intermediate.service.environmental.movement;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
                if (taskInfo.destination().equals(currentPosition)) {
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
}
