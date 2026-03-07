// src/main/java/org/unicam/intermediate/listener/execution/UnbindingExecutionListener.java

package org.unicam.intermediate.listener.execution;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.models.WaitingBinding;
import org.unicam.intermediate.models.enums.TaskType;
import org.unicam.intermediate.models.pojo.Place;
import org.unicam.intermediate.service.MessageFlowRegistry;
import org.unicam.intermediate.service.MessageFlowRegistry.MessageFlowBinding;
import org.unicam.intermediate.service.environmental.BindingService;
import org.unicam.intermediate.service.environmental.ProximityService;
import org.unicam.intermediate.service.participant.ParticipantService;

import java.time.Instant;
import java.util.Optional;

import static org.unicam.intermediate.utils.Constants.unbindingExecutionListenerBeanName;

@Slf4j
@Component(unbindingExecutionListenerBeanName)
@AllArgsConstructor
public class UnbindingExecutionListener implements ExecutionListener {

    private final BindingService bindingService;
    private final RuntimeService runtimeService;
    private final MessageFlowRegistry messageFlowRegistry;
    private final ParticipantService participantService;
    private final ProximityService proximityService;

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
        String activityName = execution.getCurrentActivityName();

        // Get message flow unbinding info
        MessageFlowBinding flowBinding = messageFlowRegistry.getFlowBinding(processDefinitionId, activityId);

        if (flowBinding == null) {
            // For unbinding tasks without explicit message flow, try to resolve from context
            log.warn("[UNBINDING] No message flow found for task: {}. Attempting to resolve from context.", activityId);
            handleUnbindingWithoutFlow(execution);
            return;
        }

        // Determine participants from flow
        String currentParticipantRef = activityId.equals(flowBinding.getSourceTaskRef())
                ? flowBinding.getSourceParticipantRef()
                : flowBinding.getTargetParticipantRef();

        String targetParticipantRef = activityId.equals(flowBinding.getSourceTaskRef())
                ? flowBinding.getTargetParticipantRef()
                : flowBinding.getSourceParticipantRef();

        log.info("[UNBINDING] Task {} started - Participant {} requesting unbind from {}",
                activityId, currentParticipantRef, targetParticipantRef);

        // Check if the other participant is already waiting to unbind
        Optional<WaitingBinding> waitingUnbinding = bindingService.findWaitingUnbinding(
                businessKey, currentParticipantRef);

        if (waitingUnbinding.isPresent()) {
            WaitingBinding match = waitingUnbinding.get();

            // Check if both participants are in the same place for safe unbinding
            Place unbindingPlace = proximityService.getBindingPlace(
                    currentParticipantRef, match.getCurrentParticipantId());

            if (unbindingPlace != null) {
                log.info("[UNBINDING] SUCCESS - Both participants in same place: {} ({}). Safe to unbind.",
                        unbindingPlace.getId(), unbindingPlace.getName());

                // Store unbinding location and timestamp
                execution.setVariable("unbindingPlaceId", unbindingPlace.getId());
                execution.setVariable("unbindingPlaceName", unbindingPlace.getName());
                execution.setVariable("unbindingTimestamp", Instant.now().toString());

                // Clear any binding variables from earlier
                execution.removeVariable("bindingPlaceId");
                execution.removeVariable("bindingPlaceName");

                // Remove from waiting and signal both processes
                bindingService.removeWaitingUnbinding(businessKey, currentParticipantRef);
                runtimeService.signal(match.getExecutionId());

                // Mark this execution as completed
                execution.setVariable("unbindingCompleted_" + activityId, true);

                log.info("[UNBINDING] COMPLETED - Participants {} and {} successfully unbound at {}",
                        currentParticipantRef, match.getCurrentParticipantId(), unbindingPlace.getName());

            } else {
                // Both want to unbind but not in same place
                ProximityService.BindingReadiness readiness = proximityService.checkBindingReadiness(
                        currentParticipantRef, match.getCurrentParticipantId());

                log.warn("[UNBINDING] CANNOT UNBIND - {}. Participants must be in same place for safe unbinding.",
                        readiness.message());

                // Keep both waiting - add current one back to waiting list
                WaitingBinding newWaiting = new WaitingBinding(
                        processDefinitionId,
                        targetParticipantRef,
                        currentParticipantRef,
                        businessKey,
                        execution.getId(),
                        TaskType.UNBINDING,
                        Instant.now()
                );
                bindingService.addWaitingUnbinding(newWaiting);

                // Store status for monitoring
                execution.setVariable("unbindingWaitReason_" + activityId, readiness.message());
            }

        } else {
            // First participant requesting unbind - add to waiting
            WaitingBinding newWaiting = new WaitingBinding(
                    processDefinitionId,
                    targetParticipantRef,
                    currentParticipantRef,
                    businessKey,
                    execution.getId(),
                    TaskType.UNBINDING,
                    Instant.now()
            );
            bindingService.addWaitingUnbinding(newWaiting);

            log.info("[UNBINDING] WAITING - {} added to waiting list. Waiting for {} to also request unbind.",
                    currentParticipantRef, targetParticipantRef);

            // Store waiting status
            execution.setVariable("unbindingWaitingFor_" + activityId, targetParticipantRef);
        }
    }

    /**
     * Handle unbinding when no explicit message flow is defined
     * This uses the last bound participant information
     */
    private void handleUnbindingWithoutFlow(DelegateExecution execution) {
        String activityId = execution.getCurrentActivityId();
        String businessKey = execution.getBusinessKey();

        // Try to get participant info from process variables (set during binding)
        String currentParticipantId = (String) execution.getVariable("currentParticipantId");
        String boundParticipantId = (String) execution.getVariable("boundParticipantId");

        if (currentParticipantId == null || boundParticipantId == null) {
            log.error("[UNBINDING] Cannot determine participants for unbinding. Missing binding context.");
            return;
        }

        log.info("[UNBINDING] Resolved from context - {} unbinding from {}",
                currentParticipantId, boundParticipantId);

        // Check if other is waiting
        Optional<WaitingBinding> waitingUnbinding = bindingService.findWaitingUnbinding(
                businessKey, currentParticipantId);

        if (waitingUnbinding.isPresent()) {
            WaitingBinding match = waitingUnbinding.get();

            Place unbindingPlace = proximityService.getBindingPlace(
                    currentParticipantId, match.getCurrentParticipantId());

            if (unbindingPlace != null) {
                log.info("[UNBINDING] Both participants ready and in place: {}", unbindingPlace.getName());

                execution.setVariable("unbindingPlaceId", unbindingPlace.getId());
                execution.setVariable("unbindingPlaceName", unbindingPlace.getName());

                bindingService.removeWaitingUnbinding(businessKey, currentParticipantId);
                runtimeService.signal(match.getExecutionId());
                execution.setVariable("unbindingCompleted_" + activityId, true);

            } else {
                log.warn("[UNBINDING] Participants not in same place - cannot unbind safely");

                WaitingBinding newWaiting = new WaitingBinding(
                        execution.getProcessDefinitionId(),
                        boundParticipantId,
                        currentParticipantId,
                        businessKey,
                        execution.getId(),
                        TaskType.UNBINDING,
                        Instant.now()
                );
                bindingService.addWaitingUnbinding(newWaiting);
            }
        } else {
            WaitingBinding newWaiting = new WaitingBinding(
                    execution.getProcessDefinitionId(),
                    boundParticipantId,
                    currentParticipantId,
                    businessKey,
                    execution.getId(),
                    TaskType.UNBINDING,
                    Instant.now()
            );
            bindingService.addWaitingUnbinding(newWaiting);

            log.info("[UNBINDING] First participant {} waiting for {} to unbind",
                    currentParticipantId, boundParticipantId);
        }
    }

    private void handleUnbindingEnd(DelegateExecution execution) {
        String activityId = execution.getCurrentActivityId();
        String businessKey = execution.getBusinessKey();
        String processDefinitionId = execution.getProcessDefinitionId();

        MessageFlowBinding flowBinding = messageFlowRegistry.getFlowBinding(processDefinitionId, activityId);

        if (flowBinding != null) {
            String participantRef = activityId.equals(flowBinding.getSourceTaskRef())
                    ? flowBinding.getSourceParticipantRef()
                    : flowBinding.getTargetParticipantRef();

            bindingService.removeWaitingUnbinding(businessKey, participantRef);

            log.info("[UNBINDING] Task {} ended for participant {}", activityId, participantRef);
        } else {
            // Try to clean up using stored participant ID
            String currentParticipantId = (String) execution.getVariable("currentParticipantId");
            if (currentParticipantId != null) {
                bindingService.removeWaitingUnbinding(businessKey, currentParticipantId);
            }
        }

        execution.removeVariable("unbindingCompleted_" + activityId);
        execution.removeVariable("unbindingWaitReason_" + activityId);
        execution.removeVariable("unbindingWaitingFor_" + activityId);

        execution.removeVariable("boundParticipantId");

        log.info("[UNBINDING] Task {} cleanup completed", activityId);
    }
}