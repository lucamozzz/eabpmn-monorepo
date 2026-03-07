package org.unicam.intermediate.service.environmental;

import lombok.AllArgsConstructor;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.unicam.intermediate.utils.BooleanEvaluation.BooleanExpressionEvaluator;
import org.unicam.intermediate.utils.BooleanEvaluation.PlaceExpressionParser;

@AllArgsConstructor
public class EvaluationService {

    private final TemperatureService temperatureService;

    public void evaluatePlaceCondition(DelegateExecution execution, String expressionText, String outputvariableName) {
        PlaceExpressionParser.ConditionParts cp =  PlaceExpressionParser.parse(expressionText);
        double temperature = temperatureService.getTemperatureFromPlace(cp.placeId);
        double threshold = Double.parseDouble(cp.value);
        var evaluation = BooleanExpressionEvaluator.evaluate(temperature, cp.operator, threshold);
        execution.setVariable(outputvariableName, evaluation);
    }
}