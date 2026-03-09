// src/main/java/org/unicam/intermediate/listener/execution/BindingExecutionListener.java

package org.unicam.intermediate.listener.execution;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.models.Participant;
import org.unicam.intermediate.service.MessageFlowRegistry;
import org.unicam.intermediate.service.MessageFlowRegistry.MessageFlowBinding;
import org.unicam.intermediate.service.environmental.binding.BindingTaskRegistry;
import org.unicam.intermediate.service.participant.ParticipantService;
import org.unicam.intermediate.service.participant.UserParticipantMappingService;

import static org.unicam.intermediate.utils.Constants.bindingExecutionListenerBeanName;

@Slf4j
@Component(bindingExecutionListenerBeanName)
@AllArgsConstructor
public class BindingExecutionListener implements ExecutionListener {

    private final BindingTaskRegistry bindingTaskRegistry;
    private final MessageFlowRegistry messageFlowRegistry;
    private final ParticipantService participantService;
    private final UserParticipantMappingService userParticipantMapping;

    @Override
    public void notify(DelegateExecution execution) {
        if (EVENTNAME_START.equals(execution.getEventName())) {
            handleBindingStart(execution);
        } else if (EVENTNAME_END.equals(execution.getEventName())) {
            handleBindingEnd(execution);
        }
    }

    private void handleBindingStart(DelegateExecution execution) {
        String processDefinitionId = execution.getProcessDefinitionId();
        String activityId = execution.getCurrentActivityId();
        String businessKey = execution.getBusinessKey();

        // Get message flow binding info
        MessageFlowBinding flowBinding = messageFlowRegistry.getFlowBinding(processDefinitionId, activityId);
        if (flowBinding == null) {
            log.error("[BINDING] No message flow found for task: {} in process: {}", activityId, processDefinitionId);
            return;
        }

        // Determine participant references from binding flow
        String targetParticipantRef = activityId.equals(flowBinding.getSourceTaskRef())
                ? flowBinding.getTargetParticipantRef()
                : flowBinding.getSourceParticipantRef();

        // Resolve actual participant IDs from execution context
        Participant currentParticipant = participantService.resolveCurrentParticipant(execution);
        Participant targetParticipant = participantService.resolveTargetParticipant(execution, targetParticipantRef);

        if (currentParticipant == null || targetParticipant == null) {
            log.error("[BINDING] Failed to resolve participants for task {}: current={}, target={}",
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

            log.info("[BINDING] Auto-registered user {} as participant {} for BK {}",
                    userId, currentParticipantId, businessKey);
        }

        log.info("[BINDING] Task {} started - Participant {} ({}) waiting for {} ({})",
                activityId, currentParticipant.getLogDisplayName(), currentParticipantId,
                targetParticipant.getLogDisplayName(), targetParticipantId);

        // Registration and pairing are fully managed by the binding task registry.
        bindingTaskRegistry.registerTask(
            businessKey,
            currentParticipantId,
            targetParticipantId,
            execution.getId()
        );
    }

    private void handleBindingEnd(DelegateExecution execution) {
        String activityId = execution.getCurrentActivityId();
        String businessKey = execution.getBusinessKey();
        String processDefinitionId = execution.getProcessDefinitionId();

        MessageFlowBinding flowBinding = messageFlowRegistry.getFlowBinding(processDefinitionId, activityId);
        if (flowBinding != null) {
            Participant participant = participantService.resolveCurrentParticipant(execution);
            if (participant != null) {
                bindingTaskRegistry.removeTask(businessKey, participant.getId());
            } else {
                log.warn("[BINDING] Could not resolve participant for task end cleanup: {}", activityId);
            }
        }

        // Clean up variables
        execution.removeVariable("bindingCompleted_" + activityId);
        log.info("[BINDING] Task {} ended", activityId);
    }
}