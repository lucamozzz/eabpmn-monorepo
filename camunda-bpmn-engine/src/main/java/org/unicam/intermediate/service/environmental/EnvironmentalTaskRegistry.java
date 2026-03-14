package org.unicam.intermediate.service.environmental;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.record.EnvironmentalTaskInfo;
import org.unicam.intermediate.service.participant.ParticipantDataService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class EnvironmentalTaskRegistry {

    private static final Pattern GUARD_PATTERN = Pattern.compile(
            "^([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]+)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$"
    );

    private static final String BPMN_ERROR_CODE_VAR = "__spaceBpmnErrorCode";
    private static final String BPMN_ERROR_MESSAGE_VAR = "__spaceBpmnErrorMessage";
    private static final String FAILED_ACTION_ERROR_CODE = "failedAction";

    private final EnvironmentDataService environmentDataService;
    private final RuntimeService runtimeService;
    private final ParticipantDataService participantDataService;

    // executionId -> active environmental task waiting for guard=true
    private final Map<String, EnvironmentalTaskInfo> activeEnvironmentalTasks = new ConcurrentHashMap<>();
    // executionId -> epoch millis when action timer started
    private final Map<String, Long> actionTimerStartByExecutionId = new ConcurrentHashMap<>();

    public EnvironmentalTaskRegistry(EnvironmentDataService environmentDataService,
                                     RuntimeService runtimeService,
                                     ParticipantDataService participantDataService) {
        this.environmentDataService = environmentDataService;
        this.runtimeService = runtimeService;
        this.participantDataService = participantDataService;
    }

    public void registerTask(String executionId, String activityId, String guardExpression, String action, String participantId, Double timer) {
        if (executionId == null || activityId == null) {
            log.warn("[EnvironmentalRegistry] Cannot register task with null execution/activity id");
            return;
        }

        String normalizedGuard = guardExpression != null ? guardExpression.trim() : "";
        String normalizedAction = action != null ? action.trim() : "";
        EnvironmentalTaskInfo info = new EnvironmentalTaskInfo(executionId, activityId, normalizedGuard, normalizedAction, participantId, timer);
        activeEnvironmentalTasks.put(executionId, info);

        log.info("[EnvironmentalRegistry] Registered environmental task | activity={} | execution={} | guard='{}' | action='{}' | participantId={} | timer={}",
                activityId,
                executionId,
                normalizedGuard.isEmpty() ? "(empty)" : normalizedGuard,
            normalizedAction.isEmpty() ? "(empty)" : normalizedAction,
            participantId != null ? participantId : "(empty)",
            timer != null ? timer : "(empty)");
    }

    public void removeTask(String executionId) {
        if (executionId == null) {
            return;
        }

        EnvironmentalTaskInfo removed = activeEnvironmentalTasks.remove(executionId);
        actionTimerStartByExecutionId.remove(executionId);
        if (removed != null) {
            log.info("[EnvironmentalRegistry] Removed environmental task | activity={} | execution={}",
                    removed.activityId(), removed.executionId());
        }
    }

    @Scheduled(fixedRate = 2000)
    public void checkEnvironmentalGuards() {
        if (activeEnvironmentalTasks.isEmpty()) {
            return;
        }

        List<String> completedExecutions = new ArrayList<>();

        for (EnvironmentalTaskInfo taskInfo : activeEnvironmentalTasks.values()) {
            boolean guardSatisfied = evaluateGuard(taskInfo.guardExpression(), taskInfo.activityId(), taskInfo.participantId());
            if (!guardSatisfied) {
                continue;
            }

            ActionCheckResult actionCheckResult = evaluateActionWithOptionalTimer(taskInfo);
            if (actionCheckResult == ActionCheckResult.WAIT) {
                continue;
            }

            try {
                if (actionCheckResult == ActionCheckResult.TIMEOUT) {
                    runtimeService.setVariableLocal(taskInfo.executionId(), BPMN_ERROR_CODE_VAR, FAILED_ACTION_ERROR_CODE);
                    runtimeService.setVariableLocal(
                            taskInfo.executionId(),
                            BPMN_ERROR_MESSAGE_VAR,
                            String.format("Action '%s' did not become true within timer for activity '%s'",
                                    taskInfo.action(), taskInfo.activityId())
                    );
                }

                completedExecutions.add(taskInfo.executionId());
                runtimeService.signal(taskInfo.executionId());
                if (actionCheckResult == ActionCheckResult.TIMEOUT) {
                    log.warn("[EnvironmentalRegistry] Action timer expired -> raised '{}' | activity={} | execution={} | action='{}' | timer={}",
                            FAILED_ACTION_ERROR_CODE,
                            taskInfo.activityId(),
                            taskInfo.executionId(),
                            taskInfo.action(),
                            taskInfo.timer());
                } else {
                    log.info("[EnvironmentalRegistry] Guard+Action satisfied -> task completed | activity={} | execution={} | guard='{}' | action='{}'",
                        taskInfo.activityId(),
                        taskInfo.executionId(),
                        taskInfo.guardExpression(),
                        taskInfo.action());
                }
            } catch (Exception e) {
                log.error("[EnvironmentalRegistry] Failed to signal execution {} for activity {}: {}",
                        taskInfo.executionId(), taskInfo.activityId(), e.getMessage(), e);
            }
        }

        completedExecutions.forEach(this::removeTask);
    }

    private boolean evaluateGuard(String guardExpression, String activityId, String participantId) {
        if (guardExpression == null || guardExpression.isBlank()) {
            log.info("[ENVIRONMENTAL] Empty guard expression for activity {} -> auto-pass", activityId);
            return true;
        }

        String resolvedExpression = resolveMyPlace(guardExpression, participantId, activityId);
        Matcher matcher = GUARD_PATTERN.matcher(resolvedExpression.trim());
        if (!matcher.matches()) {
            log.warn("[ENVIRONMENTAL] Invalid guard format for activity {}: '{}'", activityId, guardExpression);
            return false;
        }

        String placeId = matcher.group(1);
        String attributeKey = matcher.group(2);
        String operator = matcher.group(3);
        String expectedRaw = unquote(matcher.group(4).trim());

        // participant.position == placeRef notation
        if ("position".equals(attributeKey)) {
            return evaluateParticipantPosition(placeId, operator, expectedRaw, activityId);
        }

        Optional<Object> actualValueOpt = environmentDataService.getPhysicalPlaceAttribute(placeId, attributeKey);
        if (actualValueOpt.isEmpty()) {
            log.debug("[ENVIRONMENTAL] Attribute '{}.{}' not found for activity {}", placeId, attributeKey, activityId);
            return false;
        }

        Object actualValue = actualValueOpt.get();
        boolean result = compare(actualValue, operator, expectedRaw);

        log.debug("[ENVIRONMENTAL] Guard evaluation | activity={} | expr='{}' | actual='{}' -> {}",
                activityId, resolvedExpression, actualValue, result);

        return result;
    }

    private boolean evaluateParticipantPosition(String participantRef, String operator, String expectedPlaceRef, String activityId) {
        Optional<String> currentPositionOpt = participantDataService.getParticipant(participantRef)
                .map(p -> p.getPosition());

        if (currentPositionOpt.isEmpty() || currentPositionOpt.get() == null) {
            log.debug("[ENVIRONMENTAL] Position check: participant '{}' not found or has no position for activity {}",
                    participantRef, activityId);
            return false;
        }

        String currentPosition = currentPositionOpt.get();

        // Resolve right-hand side place reference to canonical id (by id or name)
        String resolvedExpected = environmentDataService.resolvePhysicalPlaceId(expectedPlaceRef)
                .or(() -> environmentDataService.resolveLogicalPlaceId(expectedPlaceRef))
                .orElse(expectedPlaceRef);

        // Resolve participant's current position to canonical id as well
        String resolvedPosition = environmentDataService.resolvePhysicalPlaceId(currentPosition)
                .orElse(currentPosition);

        boolean result = compare(resolvedPosition, operator, resolvedExpected);

        log.debug("[ENVIRONMENTAL] Position guard | activity={} | participant='{}' | position='{}' | op='{}' | expected='{}' -> {}",
                activityId, participantRef, resolvedPosition, operator, resolvedExpected, result);

        return result;
    }

    private String resolveMyPlace(String expression, String participantId, String activityId) {
        if (expression == null || !expression.contains("myPlace()")) {
            return expression;
        }
        if (participantId == null) {
            log.warn("[ENVIRONMENTAL] myPlace() used but no participantId available for activity {}", activityId);
            return expression;
        }
        return participantDataService.getParticipant(participantId)
                .map(p -> {
                    String pos = p.getPosition();
                    if (pos == null) {
                        log.warn("[ENVIRONMENTAL] myPlace() used but participant '{}' has no position for activity {}", participantId, activityId);
                        return expression;
                    }
                    log.debug("[ENVIRONMENTAL] Resolved myPlace() -> '{}' for participant '{}' activity {}",
                            pos, participantId, activityId);
                    return expression.replace("myPlace()", pos);
                })
                .orElseGet(() -> {
                    log.warn("[ENVIRONMENTAL] myPlace() used but participant '{}' not found for activity {}", participantId, activityId);
                    return expression;
                });
    }

    private boolean compare(Object actualValue, String operator, String expectedRaw) {
        if (actualValue == null) {
            return false;
        }

        String actualText = String.valueOf(actualValue).trim();

        Double actualNumber = toNumber(actualText);
        Double expectedNumber = toNumber(expectedRaw);

        if (actualNumber != null && expectedNumber != null) {
            return switch (operator) {
                case "==" -> Double.compare(actualNumber, expectedNumber) == 0;
                case "!=" -> Double.compare(actualNumber, expectedNumber) != 0;
                case ">" -> actualNumber > expectedNumber;
                case "<" -> actualNumber < expectedNumber;
                case ">=" -> actualNumber >= expectedNumber;
                case "<=" -> actualNumber <= expectedNumber;
                default -> false;
            };
        }

        return switch (operator) {
            case "==" -> actualText.equals(expectedRaw);
            case "!=" -> !actualText.equals(expectedRaw);
            default -> false;
        };
    }

    private Double toNumber(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String unquote(String raw) {
        if ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'"))) {
            return raw.substring(1, raw.length() - 1).trim();
        }
        return raw;
    }

    private boolean evaluateActionGuard(String action, String activityId, String participantId) {
        if (action == null || action.isBlank()) {
            log.debug("[ENVIRONMENTAL] Empty action for activity {} -> action-check auto-pass", activityId);
            return true;
        }

        return switch (action.trim().toLowerCase()) {
            case "turnlightson" -> evaluateGuard("place1.light == on", activityId + "#action:turnLightsOn", participantId);
            case "turnlightsoff" -> evaluateGuard("place1.light == off", activityId + "#action:turnLightsOff", participantId);
            default -> {
                log.warn("[ENVIRONMENTAL] Unknown action '{}' for activity {} -> action-check fails", action, activityId);
                yield false;
            }
        };
    }

    private ActionCheckResult evaluateActionWithOptionalTimer(EnvironmentalTaskInfo taskInfo) {
        String action = taskInfo.action();
        String executionId = taskInfo.executionId();
        String activityId = taskInfo.activityId();

        // Timer is meaningful only when an action is configured.
        if (action == null || action.isBlank()) {
            actionTimerStartByExecutionId.remove(executionId);
            return ActionCheckResult.SATISFIED;
        }

        boolean actionSatisfied = evaluateActionGuard(action, activityId, taskInfo.participantId());
        if (actionSatisfied) {
            actionTimerStartByExecutionId.remove(executionId);
            return ActionCheckResult.SATISFIED;
        }

        Double timerSeconds = taskInfo.timer();
        if (timerSeconds == null || timerSeconds <= 0) {
            return ActionCheckResult.WAIT;
        }

        long startTime = actionTimerStartByExecutionId.computeIfAbsent(executionId, ignored -> {
            long now = System.currentTimeMillis();
            log.info("[EnvironmentalRegistry] Action timer started | activity={} | execution={} | action='{}' | timer={}s",
                    activityId, executionId, action, timerSeconds);
            return now;
        });

        long timeoutMillis = Math.round(timerSeconds * 1000.0d);
        long elapsed = System.currentTimeMillis() - startTime;

        if (elapsed >= timeoutMillis) {
            actionTimerStartByExecutionId.remove(executionId);
            return ActionCheckResult.TIMEOUT;
        }

        return ActionCheckResult.WAIT;
    }

    private enum ActionCheckResult {
        SATISFIED,
        WAIT,
        TIMEOUT
    }
}
