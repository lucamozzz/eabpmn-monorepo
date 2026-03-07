package org.unicam.intermediate.listener;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.bpmn.listener.DelegateExpressionExecutionListener;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.el.ExpressionManager;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.activity.WaitStateActivity;
import org.unicam.intermediate.service.MessageFlowRegistry;
import org.unicam.intermediate.utils.Constants;

import java.util.Collections;

import static org.unicam.intermediate.utils.Constants.*;

@Component
@Slf4j
public class DynamicParseListener extends AbstractBpmnParseListener {

    @Autowired
    @Lazy  // Break circular dependency
    private MessageFlowRegistry messageFlowRegistry;

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
        // Message flow registration will be done post-deployment
        log.debug("[DynamicParseListener] Process parsed: {}", processDefinition.getId());
    }

    @Override
    public void parseTask(Element taskElement, ScopeImpl scope, ActivityImpl activity) {
        Element extensions = taskElement.element("extensionElements");
        if (extensions == null) {
            log.debug("[DynamicParseListener] No extensions found for activity: {}", activity.getId());
            return;
        }

        Element typeElement = extensions.elementNS(Constants.SPACE_NS, "type");
        if (typeElement == null) {
            log.debug("[DynamicParseListener] No space:type found for activity: {}", activity.getId());
            return;
        }

        String typeValue = typeElement.getText();
        if (typeValue == null || typeValue.trim().isEmpty()) {
            log.warn("[DynamicParseListener] Empty space:type found for activity: {}", activity.getId());
            return;
        }

        typeValue = typeValue.trim();
        String listenerBeanName = getListenerBeanName(typeValue);

        if (listenerBeanName == null) {
            log.warn("[DynamicParseListener] No listener configured for task type '{}' on activity '{}'",
                    typeValue, activity.getId());
            return;
        }

        try {
            // Set wait state behavior for all dynamic task types
            activity.setActivityBehavior(new WaitStateActivity());

            // Create expression-based listener
            ExpressionManager exprMgr = Context.getProcessEngineConfiguration().getExpressionManager();
            String exprString = "${" + listenerBeanName + "}";
            var expression = exprMgr.createExpression(exprString);

            ExecutionListener listener = new DelegateExpressionExecutionListener(expression, Collections.emptyList());
            activity.addListener(ExecutionListener.EVENTNAME_START, listener);
            activity.addListener(ExecutionListener.EVENTNAME_END, listener);

            log.info("[DynamicParseListener] Configured activity '{}' with type '{}' -> listener ${{{}}}",
                    activity.getId(), typeValue, listenerBeanName);

        } catch (Exception e) {
            log.error("[DynamicParseListener] Failed to configure task type '{}' for activity '{}': {}",
                    typeValue, activity.getId(), e.getMessage(), e);
        }
    }

    private String getListenerBeanName(String typeValue) {
        return switch (typeValue.toLowerCase()) {
            case "movement" -> movementExecutionListenerBeanName;
            case "binding" -> bindingExecutionListenerBeanName;
            case "unbinding" -> unbindingExecutionListenerBeanName;
            default -> {
                log.warn("[DynamicParseListener] Unknown task type: {}", typeValue);
                yield null;
            }
        };
    }
}