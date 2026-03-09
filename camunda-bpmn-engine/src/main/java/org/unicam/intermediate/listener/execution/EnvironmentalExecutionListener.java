package org.unicam.intermediate.listener.execution;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.Task;
import org.camunda.bpm.model.xml.instance.DomElement;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.unicam.intermediate.service.environmental.EnvironmentalTaskRegistry;
import org.springframework.stereotype.Component;

import static org.unicam.intermediate.utils.Constants.SPACE_NS;
import static org.unicam.intermediate.utils.Constants.environmentalExecutionListenerBeanName;

@Slf4j
@Component(environmentalExecutionListenerBeanName)
public class EnvironmentalExecutionListener implements ExecutionListener {

    private static final String GUARD_LOCAL_NAME = "guard";
    private final EnvironmentalTaskRegistry environmentalTaskRegistry;

    public EnvironmentalExecutionListener(EnvironmentalTaskRegistry environmentalTaskRegistry) {
        this.environmentalTaskRegistry = environmentalTaskRegistry;
    }

    @Override
    public void notify(DelegateExecution execution) {
        if (EVENTNAME_START.equals(execution.getEventName())) {
            handleEnvironmentalStart(execution);
        } else if (EVENTNAME_END.equals(execution.getEventName())) {
            handleEnvironmentalEnd(execution);
        }
    }

    private void handleEnvironmentalStart(DelegateExecution execution) {
        String guardValue = extractGuardValue(execution);
        environmentalTaskRegistry.registerTask(execution.getId(), execution.getCurrentActivityId(), guardValue);

        log.info("[ENVIRONMENTAL] WAITING | Activity: {} - {} | Guard: {}",
                execution.getCurrentActivityId(),
                execution.getCurrentActivityName() != null ? execution.getCurrentActivityName() : "(unnamed)",
                guardValue != null && !guardValue.isBlank() ? guardValue : "(empty)");
    }

    private void handleEnvironmentalEnd(DelegateExecution execution) {
        environmentalTaskRegistry.removeTask(execution.getId());
        log.info("[ENVIRONMENTAL] Task {} ended", execution.getCurrentActivityId());
    }

    private String extractGuardValue(DelegateExecution execution) {
        ModelElementInstance modelElement = execution.getBpmnModelElementInstance();
        if (!(modelElement instanceof Task task)) {
            return null;
        }

        ExtensionElements extensionElements = task.getExtensionElements();
        if (extensionElements == null) {
            return null;
        }

        return extensionElements.getDomElement().getChildElements().stream()
                .filter(this::isSpaceGuard)
                .map(DomElement::getTextContent)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private boolean isSpaceGuard(DomElement domElement) {
        return GUARD_LOCAL_NAME.equalsIgnoreCase(domElement.getLocalName())
                && SPACE_NS.getNamespaceUri().equals(domElement.getNamespaceURI());
    }
}
