// src/main/java/org/unicam/intermediate/listener/execution/BindingExecutionListener.java

package org.unicam.intermediate.listener.execution;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.models.Participant;
import org.unicam.intermediate.models.WaitingBinding;
import org.unicam.intermediate.models.enums.TaskType;
import org.unicam.intermediate.models.pojo.Place;
import org.unicam.intermediate.service.MessageFlowRegistry;
import org.unicam.intermediate.service.MessageFlowRegistry.MessageFlowBinding;
import org.unicam.intermediate.service.environmental.BindingService;
import org.unicam.intermediate.service.environmental.ProximityService;
import org.unicam.intermediate.service.participant.ParticipantService;
import org.unicam.intermediate.service.participant.UserParticipantMappingService;

import java.time.Instant;
import java.util.Optional;

import static org.unicam.intermediate.utils.Constants.bindingExecutionListenerBeanName;

@Slf4j
@Component(bindingExecutionListenerBeanName)
@AllArgsConstructor
public class BindingExecutionListener implements ExecutionListener {

    private final BindingService bindingService;
    private final RuntimeService runtimeService;
    private final MessageFlowRegistry messageFlowRegistry;
    private final ProximityService proximityService;
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



        // Determine participants
        String currentParticipantRef = activityId.equals(flowBinding.getSourceTaskRef())
                ? flowBinding.getSourceParticipantRef()
                : flowBinding.getTargetParticipantRef();

        String targetParticipantRef = activityId.equals(flowBinding.getSourceTaskRef())
                ? flowBinding.getTargetParticipantRef()
                : flowBinding.getSourceParticipantRef();

        String userId = (String) execution.getVariable("userId");
        if (userId != null && currentParticipantRef != null && businessKey != null) {
            userParticipantMapping.registerUserAsParticipant(
                    businessKey,
                    userId,
                    currentParticipantRef
            );

            log.info("[BINDING] Auto-registered user {} as participant {} for BK {}",
                    userId, currentParticipantRef, businessKey);
        }

        log.info("[BINDING] Task {} started - Participant {} waiting for {}",
                activityId, currentParticipantRef, targetParticipantRef);

        // Check if the other participant is already waiting
        Optional<WaitingBinding> waiting = bindingService.findWaitingBinding(businessKey, currentParticipantRef);

        if (waiting.isPresent()) {
            WaitingBinding match = waiting.get();

            // Check if both participants are in the same place
            Place bindingPlace = proximityService.getBindingPlace(
                    currentParticipantRef, match.getCurrentParticipantId());

            if (bindingPlace != null) {
                log.info("[BINDING] SUCCESS - Both participants in same place: {} ({})",
                        bindingPlace.getId(), bindingPlace.getName());

                // Store the binding location
                execution.setVariable("bindingPlaceId", bindingPlace.getId());
                execution.setVariable("bindingPlaceName", bindingPlace.getName());

                // Remove waiting and signal both
                bindingService.removeWaitingBinding(businessKey, currentParticipantRef);
                runtimeService.signal(match.getExecutionId());
                execution.setVariable("bindingCompleted_" + activityId, true);

            } else {
                // Both waiting but not in same place - check why
                ProximityService.BindingReadiness readiness = proximityService.checkBindingReadiness(
                        currentParticipantRef, match.getCurrentParticipantId());

                log.warn("[BINDING] CANNOT BIND - {}", readiness.message());

                // Keep both waiting
                WaitingBinding newWaiting = new WaitingBinding(
                        processDefinitionId,
                        targetParticipantRef,
                        currentParticipantRef,
                        businessKey,
                        execution.getId(),
                        TaskType.BINDING,
                        Instant.now()
                );
                bindingService.addWaitingBinding(newWaiting);
            }

        } else {
            // First participant - add to waiting
            WaitingBinding newWaiting = new WaitingBinding(
                    processDefinitionId,
                    targetParticipantRef,
                    currentParticipantRef,
                    businessKey,
                    execution.getId(),
                    TaskType.BINDING,
                    Instant.now()
            );
            bindingService.addWaitingBinding(newWaiting);

            log.info("[BINDING] WAITING - First participant added to waiting list");
        }
    }

    private void handleBindingEnd(DelegateExecution execution) {
        String activityId = execution.getCurrentActivityId();
        String businessKey = execution.getBusinessKey();
        String processDefinitionId = execution.getProcessDefinitionId();

        MessageFlowBinding flowBinding = messageFlowRegistry.getFlowBinding(processDefinitionId, activityId);
        if (flowBinding != null) {
            String participantRef = activityId.equals(flowBinding.getSourceTaskRef())
                    ? flowBinding.getSourceParticipantRef()
                    : flowBinding.getTargetParticipantRef();

            bindingService.removeWaitingBinding(businessKey, participantRef);
        }

        // Clean up variables
        execution.removeVariable("bindingCompleted_" + activityId);
        log.info("[BINDING] Task {} ended", activityId);
    }
}