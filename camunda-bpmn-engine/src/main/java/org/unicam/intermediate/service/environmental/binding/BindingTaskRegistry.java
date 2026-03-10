package org.unicam.intermediate.service.environmental.binding;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.record.BindingTaskInfo;
import org.unicam.intermediate.service.environmental.EnvironmentDataService;
import org.unicam.intermediate.service.participant.ParticipantDataService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class BindingTaskRegistry {

    private final EnvironmentDataService environmentDataService;
    private final ParticipantDataService participantDataService;
    private final RuntimeService runtimeService;

    // participantId -> binding task currently waiting for counterpart
    private final Map<String, BindingTaskInfo> waitingByParticipant = new ConcurrentHashMap<>();

    // pairKey -> active paired binding tasks to monitor by position
    private final Map<String, BindingPair> activePairs = new ConcurrentHashMap<>();

    public BindingTaskRegistry(EnvironmentDataService environmentDataService,
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
            log.warn("[BindingRegistry] Cannot register binding task due to null values: businessKey={}, participantId={}, targetParticipantId={}, executionId={}",
                    businessKey, participantId, targetParticipantId, executionId);
            return;
        }

        log.info("[BindingRegistry] Registering task: BK={} | {} -> {} | exec={}", 
                businessKey, participantId, targetParticipantId, executionId);

        BindingTaskInfo taskInfo = new BindingTaskInfo(businessKey, participantId, targetParticipantId, executionId);
        waitingByParticipant.put(participantId, taskInfo);
        log.info("[BindingRegistry] Registered waiting binding task | BK: {} | participant: {} -> target: {}",
                businessKey, participantId, targetParticipantId);

        log.info("[BindingRegistry] Waiting tasks after registration: {}", waitingByParticipant.entrySet().stream()
                .map(e -> e.getKey() + "=>" + e.getValue().targetParticipantId())
                .toList());

        tryActivatePair(taskInfo);
    }

    public void removeTask(String businessKey, String participantId) {
        if (businessKey == null || participantId == null) {
            return;
        }

        waitingByParticipant.remove(participantId);

        List<String> pairKeysToRemove = new ArrayList<>();
        for (Map.Entry<String, BindingPair> entry : activePairs.entrySet()) {
            BindingPair pair = entry.getValue();
            boolean sameBusinessKey = businessKey.equals(pair.first.businessKey()) && businessKey.equals(pair.second.businessKey());
            boolean participantInPair = participantId.equals(pair.first.participantId()) || participantId.equals(pair.second.participantId());
            if (sameBusinessKey && participantInPair) {
                pairKeysToRemove.add(entry.getKey());
            }
        }

        for (String pairKey : pairKeysToRemove) {
            BindingPair removed = activePairs.remove(pairKey);
            if (removed != null) {
                waitingByParticipant.remove(removed.first.participantId());
                waitingByParticipant.remove(removed.second.participantId());
                log.info("[BindingRegistry] Removed active pair {} due to task end", pairKey);
            }
        }
    }

    @Scheduled(fixedRate = 2000)
    public void checkBindingCompletion() {
        if (!waitingByParticipant.isEmpty()) {
            log.info("[BindingRegistry] Waiting for counterpart: {} participants in waiting | waiting={}", 
                    waitingByParticipant.size(),
                    waitingByParticipant.entrySet().stream()
                            .map(e -> e.getKey() + "->" + e.getValue().targetParticipantId())
                            .toList());
        }

        if (activePairs.isEmpty()) {
            return;
        }

        List<String> completedPairs = new ArrayList<>();

        for (Map.Entry<String, BindingPair> entry : activePairs.entrySet()) {
            String pairKey = entry.getKey();
            BindingPair pair = entry.getValue();

            String positionA = getParticipantPosition(pair.first.participantId());
            String positionB = getParticipantPosition(pair.second.participantId());

            log.info("[BindingRegistry] Checking pair {}: {} at {} vs {} at {}", 
                    pairKey, 
                    pair.first.participantId(), positionA,
                    pair.second.participantId(), positionB);

            if (positionA == null || positionB == null) {
                continue;
            }

            if (positionA.equals(positionB)) {
                log.info("[BindingRegistry] Pair {} reached same position '{}' -> completing binding tasks",
                        pairKey, positionA);
                try {
                    runtimeService.signal(pair.first.executionId());
                    runtimeService.signal(pair.second.executionId());
                    completedPairs.add(pairKey);
                } catch (Exception e) {
                    log.error("[BindingRegistry] Failed to signal pair {}: {}", pairKey, e.getMessage(), e);
                }
            }
        }

        for (String pairKey : completedPairs) {
            BindingPair removed = activePairs.remove(pairKey);
            if (removed != null) {
                waitingByParticipant.remove(removed.first.participantId());
                waitingByParticipant.remove(removed.second.participantId());
                log.info("[BindingRegistry] Completed and removed pair {}", pairKey);
            }
        }
    }

    private void tryActivatePair(BindingTaskInfo current) {
        log.info("[BindingRegistry] tryActivatePair: Looking for counterpart for {} -> {}",
                current.participantId(), current.targetParticipantId());
        
        BindingTaskInfo counterpart = waitingByParticipant.get(current.targetParticipantId());
        if (counterpart == null) {
            log.info("[BindingRegistry] tryActivatePair: No counterpart found for {} (waiting set: {})",
                    current.targetParticipantId(), waitingByParticipant.keySet());
            return;
        }
        
        log.info("[BindingRegistry] tryActivatePair: Found potential counterpart {} -> {}",
                counterpart.participantId(), counterpart.targetParticipantId());

        boolean sameBusinessKey = current.businessKey().equals(counterpart.businessKey());
        log.info("[BindingRegistry] tryActivatePair: Same business key? {} vs {} = {}",
                current.businessKey(), counterpart.businessKey(), sameBusinessKey);
        
        boolean participantMatch = current.participantId().equals(counterpart.targetParticipantId());
        boolean targetMatch = current.targetParticipantId().equals(counterpart.participantId());
        boolean reciprocalPair = participantMatch && targetMatch;
        
        log.info("[BindingRegistry] tryActivatePair: Reciprocal check - current.id ({}) == counterpart.target ({}) ? {} | current.target ({}) == counterpart.id ({}) ? {}",
                current.participantId(), counterpart.targetParticipantId(), participantMatch,
                current.targetParticipantId(), counterpart.participantId(), targetMatch);

        if (!sameBusinessKey || !reciprocalPair) {
            log.info("[BindingRegistry] tryActivatePair: Pair rejected - sameBusinessKey={}, reciprocalPair={}",
                    sameBusinessKey, reciprocalPair);
            return;
        }

        String pairKey = buildPairKey(current.businessKey(), current.participantId(), current.targetParticipantId());
        activePairs.put(pairKey, new BindingPair(current, counterpart));

        // Remove from waiting set once pair is active
        waitingByParticipant.remove(current.participantId());
        waitingByParticipant.remove(counterpart.participantId());

        log.info("[BindingRegistry] ✓ Activated pair {} ({} <-> {})",
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

    private record BindingPair(BindingTaskInfo first, BindingTaskInfo second) {
    }
}
