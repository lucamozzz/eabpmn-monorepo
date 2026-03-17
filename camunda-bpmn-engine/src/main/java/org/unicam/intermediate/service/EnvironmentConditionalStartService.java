package org.unicam.intermediate.service;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.xml.instance.DomElement;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts process instances when a StartEvent defines a space:guard condition
 * that becomes true against the current environment model.
 */
@Service
@Slf4j
public class EnvironmentConditionalStartService {

    private static final String SPACE_NS = "http://space";
    private static final String GUARD_LOCAL_NAME = "guard";

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final SequenceFlowGuardEvaluator sequenceFlowGuardEvaluator;

    private final Map<String, Boolean> lastSatisfiedByStartKey = new ConcurrentHashMap<>();

    public EnvironmentConditionalStartService(RepositoryService repositoryService,
                                              RuntimeService runtimeService,
                                              SequenceFlowGuardEvaluator sequenceFlowGuardEvaluator) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.sequenceFlowGuardEvaluator = sequenceFlowGuardEvaluator;
    }

    @Scheduled(fixedRate = 2000)
    public void evaluateConditionalStarts() {
        for (ProcessDefinition definition : repositoryService.createProcessDefinitionQuery().latestVersion().active().list()) {
            evaluateProcessDefinition(definition);
        }
    }

    private void evaluateProcessDefinition(ProcessDefinition definition) {
        BpmnModelInstance modelInstance = repositoryService.getBpmnModelInstance(definition.getId());
        if (modelInstance == null) {
            return;
        }

        for (StartEvent startEvent : modelInstance.getModelElementsByType(StartEvent.class)) {
            if (!(startEvent.getParentElement() instanceof Process)) {
                continue;
            }

            String guardExpression = extractGuard(startEvent.getExtensionElements());
            if (guardExpression == null || guardExpression.isBlank()) {
                continue;
            }

            String startKey = definition.getId() + ":" + startEvent.getId();
            boolean wasSatisfied = lastSatisfiedByStartKey.getOrDefault(startKey, false);
            boolean isSatisfied = sequenceFlowGuardEvaluator.evaluateAdHocGuard(
                    definition.getId(),
                    startEvent.getId(),
                    guardExpression,
                    null
            );

            if (isSatisfied && !wasSatisfied) {
                long activeInstances = runtimeService.createProcessInstanceQuery()
                        .processDefinitionId(definition.getId())
                        .active()
                        .count();

                if (activeInstances == 0) {
                    try {
                        runtimeService.createProcessInstanceById(definition.getId())
                                .startBeforeActivity(startEvent.getId())
                                .setVariable("__spaceConditionalStartEventId", startEvent.getId())
                                .setVariable("__spaceConditionalStartGuard", guardExpression)
                                .execute();

                        log.info("[EnvironmentConditionalStart] Triggered process '{}' from StartEvent '{}' with guard '{}'",
                                definition.getKey(), startEvent.getId(), guardExpression);
                    } catch (Exception e) {
                        log.error("[EnvironmentConditionalStart] Failed to trigger process '{}' for StartEvent '{}': {}",
                                definition.getKey(), startEvent.getId(), e.getMessage(), e);
                    }
                }
            }

            lastSatisfiedByStartKey.put(startKey, isSatisfied);
        }
    }

    private String extractGuard(ExtensionElements extensionElements) {
        if (extensionElements == null || extensionElements.getDomElement() == null) {
            return null;
        }

        return extensionElements.getDomElement().getChildElements().stream()
                .filter(this::isSpaceGuard)
                .map(DomElement::getTextContent)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    private boolean isSpaceGuard(DomElement domElement) {
        return GUARD_LOCAL_NAME.equalsIgnoreCase(domElement.getLocalName())
                && SPACE_NS.equals(domElement.getNamespaceURI());
    }
}
