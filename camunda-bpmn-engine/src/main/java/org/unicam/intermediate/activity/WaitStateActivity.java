package org.unicam.intermediate.activity;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.impl.bpmn.behavior.AbstractBpmnActivityBehavior;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution;

@Slf4j
public class WaitStateActivity extends AbstractBpmnActivityBehavior {

    @Override
    public void execute(ActivityExecution execution) {
        try {
            String activityId = execution.getActivity().getId();

            // Check if binding/unbinding was already successful
            Boolean bindingCompleted = (Boolean) execution.getVariable("bindingCompleted_" + activityId);
            Boolean unbindingCompleted = (Boolean) execution.getVariable("unbindingCompleted_" + activityId);

            if (Boolean.TRUE.equals(bindingCompleted) || Boolean.TRUE.equals(unbindingCompleted)) {
                log.info("[WaitStateActivity] Activity '{}' binding/unbinding already completed, continuing immediately.", activityId);
                // Clean up the variable
                execution.removeVariable("bindingCompleted_" + activityId);
                execution.removeVariable("unbindingCompleted_" + activityId);
                // Continue immediately
                leave(execution);
                return;
            }

            log.info("[WaitStateActivity] Activity '{}' has entered wait state.", activityId);
            // execution is paused until a signal is received
        } catch (Exception e) {
            log.error("[WaitStateActivity] Error while entering wait state: {}", e.getMessage(), e);
            throw new RuntimeException("Error during wait state execution", e);
        }
    }

    @Override
    public void signal(ActivityExecution execution, String signalName, Object signalData) {
        try {
            String activityId = execution.getActivity().getId();
            log.info("[WaitStateActivity] Signal received for activity '{}'. signalName={}, signalData={}",
                    activityId, signalName, signalData);

            // Clean up any binding completion variables
            execution.removeVariable("bindingCompleted_" + activityId);
            execution.removeVariable("unbindingCompleted_" + activityId);

            leave(execution);
        } catch (Exception e) {
            log.error("[WaitStateActivity] Error while processing signal: {}", e.getMessage(), e);
            throw new RuntimeException("Error during wait state signal handling", e);
        }
    }
}