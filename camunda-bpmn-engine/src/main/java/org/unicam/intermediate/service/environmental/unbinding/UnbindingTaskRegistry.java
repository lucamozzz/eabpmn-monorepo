package org.unicam.intermediate.service.environmental.unbinding;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.record.BindingTaskInfo;
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
public class UnbindingTaskRegistry {

    private final EnvironmentDataService environmentDataService;
    private final ParticipantDataService participantDataService;
    private final RuntimeService runtimeService;

    // participantId -> unbinding task currently waiting for counterpart
    private final Map<String, BindingTaskInfo> waitingByParticipant = new ConcurrentHashMap<>();

    // pairKey -> active paired unbinding tasks to monitor by position
    private final Map<String, UnbindingPair> activePairs = new ConcurrentHashMap<>();

    public UnbindingTaskRegistry(EnvironmentDataService environmentDataService,
                                 ParticipantDataService participantDataService,
                                 RuntimeService runtimeService) {
        this.environmentDataService = environmentDataService;
        this.participantDataService = participantDataService;
        this.runtimeService = runtimeService;
    }

    public void registerTask(String businessKey,
                             String participantId,
                             String targetParticipantId,
                             String executionId) {
        if (businessKey == null || participantId == null || targetParticipantId == null || executionId == null) {
            log.warn("[UnbindingRegistry] Cannot register unbinding task due to null values: businessKey={}, participantId={}, targetParticipantId={}, executionId={}",
                    businessKey, participantId, targetParticipantId, executionId);
            return;
        }

        log.info("[UnbindingRegistry] Registering task: BK={} | {} -> {} | exec={}",
                businessKey, participantId, targetParticipantId, executionId);

        BindingTaskInfo taskInfo = new BindingTaskInfo(businessKey, participantId, targetParticipantId, executionId);
        waitingByParticipant.put(participantId, taskInfo);
        log.info("[UnbindingRegistry] Registered waiting unbinding task | BK: {} | participant: {} -> target: {}",
                businessKey, participantId, targetParticipantId);

        tryActivatePair(taskInfo);
    }

    public void removeTask(String businessKey, String participantId) {
        if (businessKey == null || participantId == null) {
            return;
        }

        waitingByParticipant.remove(participantId);

        List<String> pairKeysToRemove = new ArrayList<>();
        for (Map.Entry<String, UnbindingPair> entry : activePairs.entrySet()) {
            UnbindingPair pair = entry.getValue();
            boolean sameBusinessKey = businessKey.equals(pair.first.businessKey()) && businessKey.equals(pair.second.businessKey());
            boolean participantInPair = participantId.equals(pair.first.participantId()) || participantId.equals(pair.second.participantId());
            if (sameBusinessKey && participantInPair) {
                pairKeysToRemove.add(entry.getKey());
            }
        }

        for (String pairKey : pairKeysToRemove) {
            UnbindingPair removed = activePairs.remove(pairKey);
            if (removed != null) {
                waitingByParticipant.remove(removed.first.participantId());
                waitingByParticipant.remove(removed.second.participantId());
                log.info("[UnbindingRegistry] Removed active pair {} due to task end", pairKey);
            }
        }
    }

    /**
     * Returns pending unbinding notifications for a participant in a shape compatible
     * with the mobile pending-actions endpoint.
     */
    public List<Map<String, String>> getPendingUnbindingsForParticipant(String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return List.of();
        }

        BindingTaskInfo waitingTask = waitingByParticipant.get(participantId);
        if (waitingTask != null) {
            return List.of(toUnbindingView(waitingTask, waitingTask.targetParticipantId()));
        }

        for (UnbindingPair pair : activePairs.values()) {
            if (participantId.equals(pair.first.participantId())) {
                if (!isPairAlreadyAtSamePosition(pair)) {
                    return List.of(toUnbindingView(pair.first, pair.second.participantId()));
                }
                return List.of();
            }
            if (participantId.equals(pair.second.participantId())) {
                if (!isPairAlreadyAtSamePosition(pair)) {
                    return List.of(toUnbindingView(pair.second, pair.first.participantId()));
                }
                return List.of();
            }
        }

        return List.of();
    }

    private Map<String, String> toUnbindingView(BindingTaskInfo taskInfo, String counterpartParticipantId) {
        Map<String, String> view = new LinkedHashMap<>();
        view.put("executionId", taskInfo.executionId());
        view.put("action", "unbinding");
        view.put("counterpartParticipantId", counterpartParticipantId);
        view.put("message", "Move to same place as " + counterpartParticipantId + " (unbinding)");
        return view;
    }

    private boolean isPairAlreadyAtSamePosition(UnbindingPair pair) {
        String positionA = getParticipantPosition(pair.first.participantId());
        String positionB = getParticipantPosition(pair.second.participantId());
        return positionA != null && positionA.equals(positionB);
    }

    @Scheduled(fixedRate = 2000)
    public void checkUnbindingCompletion() {
        if (activePairs.isEmpty()) {
            return;
        }

        List<String> completedPairs = new ArrayList<>();

        for (Map.Entry<String, UnbindingPair> entry : activePairs.entrySet()) {
            String pairKey = entry.getKey();
            UnbindingPair pair = entry.getValue();

            String positionA = getParticipantPosition(pair.first.participantId());
            String positionB = getParticipantPosition(pair.second.participantId());

            log.debug("[UnbindingRegistry] Checking pair {}: {} at {} vs {} at {}",
                    pairKey,
                    pair.first.participantId(), positionA,
                    pair.second.participantId(), positionB);

            if (positionA == null || positionB == null) {
                continue;
            }

            if (positionA.equals(positionB)) {
                log.info("[UnbindingRegistry] Pair {} reached same position '{}' -> completing unbinding tasks",
                        pairKey, positionA);
                try {
                    runtimeService.signal(pair.first.executionId());
                    runtimeService.signal(pair.second.executionId());
                    completedPairs.add(pairKey);
                } catch (Exception e) {
                    log.error("[UnbindingRegistry] Failed to signal pair {}: {}", pairKey, e.getMessage(), e);
                }
            }
        }

        for (String pairKey : completedPairs) {
            UnbindingPair removed = activePairs.remove(pairKey);
            if (removed != null) {
                waitingByParticipant.remove(removed.first.participantId());
                waitingByParticipant.remove(removed.second.participantId());
                log.info("[UnbindingRegistry] Completed and removed pair {}", pairKey);
            }
        }
    }

    private void tryActivatePair(BindingTaskInfo current) {
        BindingTaskInfo counterpart = waitingByParticipant.get(current.targetParticipantId());
        if (counterpart == null) {
            return;
        }

        boolean sameBusinessKey = current.businessKey().equals(counterpart.businessKey());
        boolean reciprocalPair =
                current.participantId().equals(counterpart.targetParticipantId()) &&
                current.targetParticipantId().equals(counterpart.participantId());

        if (!sameBusinessKey || !reciprocalPair) {
            return;
        }

        String pairKey = buildPairKey(current.businessKey(), current.participantId(), current.targetParticipantId());
        activePairs.put(pairKey, new UnbindingPair(current, counterpart));

        // Remove from waiting set once pair is active
        waitingByParticipant.remove(current.participantId());
        waitingByParticipant.remove(counterpart.participantId());

        log.info("[UnbindingRegistry] Activated pair {} ({} <-> {})",
                pairKey, current.participantId(), current.targetParticipantId());
    }

    private String getParticipantPosition(String participantId) {
        Optional<org.unicam.intermediate.models.pojo.Participant> participant =
                participantDataService.getParticipant(participantId);
        return participant.map(org.unicam.intermediate.models.pojo.Participant::getPosition).orElse(null);
    }

    private String buildPairKey(String businessKey, String participantA, String participantB) {
        if (participantA.compareTo(participantB) <= 0) {
            return businessKey + ":" + participantA + "<->" + participantB;
        }
        return businessKey + ":" + participantB + "<->" + participantA;
    }

    private record UnbindingPair(BindingTaskInfo first, BindingTaskInfo second) {
    }
}