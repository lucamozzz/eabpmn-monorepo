package org.unicam.intermediate.service.environmental;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.pojo.PhysicalPlace;
import org.unicam.intermediate.models.record.EnvironmentalTaskInfo;

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

    private final EnvironmentDataService environmentDataService;
    private final RuntimeService runtimeService;

    // executionId -> active environmental task waiting for guard=true
    private final Map<String, EnvironmentalTaskInfo> activeEnvironmentalTasks = new ConcurrentHashMap<>();

    public EnvironmentalTaskRegistry(EnvironmentDataService environmentDataService,
                                     RuntimeService runtimeService) {
        this.environmentDataService = environmentDataService;
        this.runtimeService = runtimeService;
    }

    public void registerTask(String executionId, String activityId, String guardExpression, String action) {
        if (executionId == null || activityId == null) {
            log.warn("[EnvironmentalRegistry] Cannot register task with null execution/activity id");
            return;
        }

        String normalizedGuard = guardExpression != null ? guardExpression.trim() : "";
        String normalizedAction = action != null ? action.trim() : "";
        EnvironmentalTaskInfo info = new EnvironmentalTaskInfo(executionId, activityId, normalizedGuard, normalizedAction);
        activeEnvironmentalTasks.put(executionId, info);

        log.info("[EnvironmentalRegistry] Registered environmental task | activity={} | execution={} | guard='{}' | action='{}'",
                activityId,
                executionId,
                normalizedGuard.isEmpty() ? "(empty)" : normalizedGuard,
                normalizedAction.isEmpty() ? "(empty)" : normalizedAction);
    }

    public void removeTask(String executionId) {
        if (executionId == null) {
            return;
        }

        EnvironmentalTaskInfo removed = activeEnvironmentalTasks.remove(executionId);
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
            boolean guardSatisfied = evaluateGuard(taskInfo.guardExpression(), taskInfo.activityId());
            if (!guardSatisfied) {
                continue;
            }

            boolean actionSatisfied = evaluateActionGuard(taskInfo.action(), taskInfo.activityId());
            if (!actionSatisfied) {
                continue;
            }

            try {
                runtimeService.signal(taskInfo.executionId());
                completedExecutions.add(taskInfo.executionId());
                log.info("[EnvironmentalRegistry] Guard+Action satisfied -> task completed | activity={} | execution={} | guard='{}' | action='{}'",
                        taskInfo.activityId(),
                        taskInfo.executionId(),
                        taskInfo.guardExpression(),
                        taskInfo.action());
            } catch (Exception e) {
                log.error("[EnvironmentalRegistry] Failed to signal execution {} for activity {}: {}",
                        taskInfo.executionId(), taskInfo.activityId(), e.getMessage(), e);
            }
        }

        completedExecutions.forEach(this::removeTask);
    }

    private boolean evaluateGuard(String guardExpression, String activityId) {
        if (guardExpression == null || guardExpression.isBlank()) {
            log.info("[ENVIRONMENTAL] Empty guard expression for activity {} -> auto-pass", activityId);
            return true;
        }

        Matcher matcher = GUARD_PATTERN.matcher(guardExpression.trim());
        if (!matcher.matches()) {
            log.warn("[ENVIRONMENTAL] Invalid guard format for activity {}: '{}'", activityId, guardExpression);
            return false;
        }

        String placeId = matcher.group(1);
        String attributeKey = matcher.group(2);
        String operator = matcher.group(3);
        String expectedRaw = unquote(matcher.group(4).trim());

        Optional<PhysicalPlace> placeOpt = environmentDataService.getPhysicalPlace(placeId);
        if (placeOpt.isEmpty()) {
            log.debug("[ENVIRONMENTAL] Place '{}' not found for activity {}", placeId, activityId);
            return false;
        }

        Map<String, Object> attributes = placeOpt.get().getAttributes();
        if (attributes == null || !attributes.containsKey(attributeKey)) {
            log.debug("[ENVIRONMENTAL] Attribute '{}.{}' not found for activity {}", placeId, attributeKey, activityId);
            return false;
        }

        Object actualValue = attributes.get(attributeKey);
        boolean result = compare(actualValue, operator, expectedRaw);

        log.debug("[ENVIRONMENTAL] Guard evaluation | activity={} | expr='{}' | actual='{}' -> {}",
                activityId, guardExpression, actualValue, result);

        return result;
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

    private boolean evaluateActionGuard(String action, String activityId) {
        if (action == null || action.isBlank()) {
            log.debug("[ENVIRONMENTAL] Empty action for activity {} -> action-check auto-pass", activityId);
            return true;
        }

        return switch (action.trim().toLowerCase()) {
            case "turnlightson" -> evaluateGuard("place1.light == on", activityId + "#action:turnLightsOn");
            default -> {
                log.warn("[ENVIRONMENTAL] Unknown action '{}' for activity {} -> action-check fails", action, activityId);
                yield false;
            }
        };
    }
}
