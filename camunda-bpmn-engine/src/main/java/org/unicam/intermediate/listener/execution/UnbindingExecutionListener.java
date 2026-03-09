// src/main/java/org/unicam/intermediate/listener/execution/UnbindingExecutionListener.java

package org.unicam.intermediate.listener.execution;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.models.Participant;
import org.unicam.intermediate.service.MessageFlowRegistry;
import org.unicam.intermediate.service.MessageFlowRegistry.MessageFlowBinding;
import org.unicam.intermediate.service.participant.ParticipantService;
import org.unicam.intermediate.service.participant.UserParticipantMappingService;
import org.unicam.intermediate.service.environmental.unbinding.UnbindingTaskRegistry;

import static org.unicam.intermediate.utils.Constants.unbindingExecutionListenerBeanName;

@Slf4j
@Component(unbindingExecutionListenerBeanName)
@AllArgsConstructor
public class UnbindingExecutionListener implements ExecutionListener {

    private final UnbindingTaskRegistry unbindingTaskRegistry;
    private final MessageFlowRegistry messageFlowRegistry;
    private final ParticipantService participantService;
    private final UserParticipantMappingService userParticipantMapping;

    @Override
    public void notify(DelegateExecution execution) {
        if (EVENTNAME_START.equals(execution.getEventName())) {
            handleUnbindingStart(execution);
        } else if (EVENTNAME_END.equals(execution.getEventName())) {
            handleUnbindingEnd(execution);
        }
    }

    private void handleUnbindingStart(DelegateExecution execution) {
        String processDefinitionId = execution.getProcessDefinitionId();
        String activityId = execution.getCurrentActivityId();
        String businessKey = execution.getBusinessKey();

        // Get message flow unbinding info
        MessageFlowBinding flowBinding = messageFlowRegistry.getFlowBinding(processDefinitionId, activityId);
        if (flowBinding == null) {
            log.error("[UNBINDING] No message flow found for task: {} in process: {}", activityId, processDefinitionId);
            return;
        }

        String targetParticipantRef = activityId.equals(flowBinding.getSourceTaskRef())
                ? flowBinding.getTargetParticipantRef()
                : flowBinding.getSourceParticipantRef();

        Participant currentParticipant = participantService.resolveCurrentParticipant(execution);
        Participant targetParticipant = participantService.resolveTargetParticipant(execution, targetParticipantRef);

        if (currentParticipant == null || targetParticipant == null) {
            log.error("[UNBINDING] Failed to resolve participants for task {}: current={}, target={}",
                activityId, currentParticipant, targetParticipant);
            return;
        }

        String currentParticipantId = currentParticipant.getId();
        String targetParticipantId = targetParticipant.getId();

        String userId = (String) execution.getVariable("userId");
        if (userId != null && currentParticipantId != null && businessKey != null) {
            userParticipantMapping.registerUserAsParticipant(
                businessKey,
                userId,
                currentParticipantId
            );

            log.info("[UNBINDING] Auto-registered user {} as participant {} for BK {}",
                userId, currentParticipantId, businessKey);
        }

        log.info("[UNBINDING] Task {} started - Participant {} ({}) waiting for unbind with {} ({})",
            activityId, currentParticipant.getLogDisplayName(), currentParticipantId,
            targetParticipant.getLogDisplayName(), targetParticipantId);

        unbindingTaskRegistry.registerTask(
            businessKey,
            currentParticipantId,
            targetParticipantId,
            execution.getId()
        );
    }

    private void handleUnbindingEnd(DelegateExecution execution) {
        String activityId = execution.getCurrentActivityId();
        String businessKey = execution.getBusinessKey();
        String processDefinitionId = execution.getProcessDefinitionId();

        MessageFlowBinding flowBinding = messageFlowRegistry.getFlowBinding(processDefinitionId, activityId);

        if (flowBinding != null) {
            Participant participant = participantService.resolveCurrentParticipant(execution);
            if (participant != null) {
                unbindingTaskRegistry.removeTask(businessKey, participant.getId());
            } else {
                log.warn("[UNBINDING] Could not resolve participant for task end cleanup: {}", activityId);
            }
        }

        execution.removeVariable("unbindingCompleted_" + activityId);
        execution.removeVariable("unbindingWaitReason_" + activityId);
        execution.removeVariable("unbindingWaitingFor_" + activityId);
        execution.removeVariable("unbindingPlaceId");
        execution.removeVariable("unbindingPlaceName");
        execution.removeVariable("unbindingTimestamp");
        execution.removeVariable("boundParticipantId");

        log.info("[UNBINDING] Task {} ended", activityId);
    }
}