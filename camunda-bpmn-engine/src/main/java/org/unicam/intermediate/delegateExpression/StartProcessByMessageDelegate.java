package org.unicam.intermediate.delegateExpression;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.Expression;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("startProcessByMessageDelegate")
@Slf4j
@RequiredArgsConstructor
public class StartProcessByMessageDelegate implements JavaDelegate {

    private final RuntimeService runtimeService;

    @Setter
    private Expression messageNameExpr;

    @Setter
    private Expression businessKeyExpr;

    @Override
    public void execute(DelegateExecution execution) {
        String messageName = getStringValue(messageNameExpr, execution);
        if (messageName == null || messageName.isBlank()) {
            throw new BpmnError("StartProcessByMessageError", "Missing messageName for message start");
        }

        String businessKey = getStringValue(businessKeyExpr, execution);
        if (businessKey == null || businessKey.isBlank()) {
            businessKey = execution.getBusinessKey();
        }

        if (businessKey == null || businessKey.isBlank()) {
            runtimeService.startProcessInstanceByMessage(messageName);
            log.info("[StartByMessage] Started process by message '{}' without businessKey", messageName);
            return;
        }

        runtimeService.startProcessInstanceByMessage(messageName, businessKey);
        log.info("[StartByMessage] Started process by message '{}' with businessKey '{}'",
                messageName, businessKey);
    }

    private String getStringValue(Expression expression, DelegateExecution execution) {
        if (expression == null) {
            return null;
        }

        Object value = expression.getValue(execution);
        return value != null ? value.toString() : null;
    }
}