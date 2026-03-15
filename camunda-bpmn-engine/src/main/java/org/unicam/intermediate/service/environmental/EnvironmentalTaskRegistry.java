package org.unicam.intermediate.service.environmental;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.dto.websocket.GpsResponse;
import org.unicam.intermediate.models.record.EnvironmentalTaskInfo;
import org.unicam.intermediate.service.participant.ParticipantDataService;
import org.unicam.intermediate.service.participant.UserParticipantMappingService;
import org.unicam.intermediate.service.websocket.WebSocketSessionManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final UserParticipantMappingService userParticipantMappingService;
    private final WebSocketSessionManager webSocketSessionManager;

    // executionId -> active environmental task waiting for guard=true
    private final Map<String, EnvironmentalTaskInfo> activeEnvironmentalTasks = new ConcurrentHashMap<>();
    // executionId -> epoch millis when action timer started
    private final Map<String, Long> actionTimerStartByExecutionId = new ConcurrentHashMap<>();
    // executionId:action -> notification already sent (dedup)
    private final Map<String, Boolean> sentActionNotifications = new ConcurrentHashMap<>();

    public EnvironmentalTaskRegistry(EnvironmentDataService environmentDataService,
                                     RuntimeService runtimeService,
                                     ParticipantDataService participantDataService,
                                     UserParticipantMappingService userParticipantMappingService,
                                     WebSocketSessionManager webSocketSessionManager) {
        this.environmentDataService = environmentDataService;
        this.runtimeService = runtimeService;
        this.participantDataService = participantDataService;
        this.userParticipantMappingService = userParticipantMappingService;
        this.webSocketSessionManager = webSocketSessionManager;
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
        clearNotificationMarkersForExecution(executionId);
        if (removed != null) {
            log.info("[EnvironmentalRegistry] Removed environmental task | activity={} | execution={}",
                    removed.activityId(), removed.executionId());
        }
    }

    /**
     * Returns action requests currently pending for the given participant.
     * A request is considered pending when:
     * 1) the task belongs to the participant,
     * 2) an action is configured,
     * 3) guard is already satisfied,
     * 4) action condition is still not satisfied.
     */
    public List<Map<String, String>> getPendingActionsForParticipant(String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return List.of();
        }

        List<Map<String, String>> pendingActions = new ArrayList<>();

        for (EnvironmentalTaskInfo taskInfo : activeEnvironmentalTasks.values()) {
            if (!participantId.equals(taskInfo.participantId())) {
                continue;
            }

            String action = taskInfo.action();
            if (action == null || action.isBlank()) {
                continue;
            }

            boolean guardSatisfied = evaluateGuard(taskInfo.guardExpression(), taskInfo.activityId(), taskInfo.participantId());
            if (!guardSatisfied) {
                continue;
            }

            boolean actionSatisfied = evaluateActionGuard(
                    action,
                    taskInfo.activityId(),
                    taskInfo.participantId(),
                    taskInfo.executionId(),
                    false
            );
            if (actionSatisfied) {
                continue;
            }

            Map<String, String> actionView = new LinkedHashMap<>();
            actionView.put("executionId", taskInfo.executionId());
            actionView.put("activityId", taskInfo.activityId());
            actionView.put("action", action);
            actionView.put("message", toHumanReadableAction(action));
            pendingActions.add(actionView);
        }

        return pendingActions;
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

    private boolean evaluateActionGuard(String action,
                                        String activityId,
                                        String participantId,
                                        String executionId,
                                        boolean notifyParticipant) {
        if (action == null || action.isBlank()) {
            log.debug("[ENVIRONMENTAL] Empty action for activity {} -> action-check auto-pass", activityId);
            return true;
        }

        return switch (action.trim().toLowerCase()) {
            case "turnlightson" -> turnLightsOn(activityId, participantId, executionId, notifyParticipant);
            case "turnlightsoff" -> turnLightsOff(activityId, participantId, executionId, notifyParticipant);
            case "cleanroom" -> cleanRoom(activityId, participantId, executionId, notifyParticipant);
            case "checkroom" -> checkRoom(activityId, participantId, executionId, notifyParticipant);
            case "leaveroom" -> leaveRoom(activityId, participantId, notifyParticipant);
            case "occupyroom" -> occupyRoom(activityId, participantId, notifyParticipant);
            default -> {
                log.warn("[ENVIRONMENTAL] Unknown action '{}' for activity {} -> action-check fails", action, activityId);
                yield false;
            }
        };
    }

    /**
     * Dedicated action handler for turning lights on.
     * Notification and guard evaluation are intentionally separate and ordered.
     */
    private boolean turnLightsOn(String activityId, String participantId, String executionId, boolean notifyParticipant) {
        if (notifyParticipant) {
            notifyTurnLightsOn(executionId, participantId);
        }
        return isTurnLightsOnSatisfied(activityId, participantId);
    }

    /**
     * Dedicated action handler for turning lights off.
     * Notification and guard evaluation are intentionally separate and ordered.
     */
    private boolean turnLightsOff(String activityId, String participantId, String executionId, boolean notifyParticipant) {
        if (notifyParticipant) {
            notifyTurnLightsOff(executionId, participantId);
        }
        return isTurnLightsOffSatisfied(activityId, participantId);
    }

    /**
     * Dedicated action handler for room cleaning.
     * Notification and guard evaluation are intentionally separate and ordered.
     */
    private boolean cleanRoom(String activityId, String participantId, String executionId, boolean notifyParticipant) {
        if (notifyParticipant) {
            notifyCleanRoom(executionId, participantId);
        }
        return isCleanRoomSatisfied(activityId, participantId);
    }

    /**
     * Dedicated action handler for room checking.
     * Notification and guard evaluation are intentionally separate and ordered.
     */
    private boolean checkRoom(String activityId, String participantId, String executionId, boolean notifyParticipant) {
        if (notifyParticipant) {
            notifyCheckRoom(executionId, participantId);
        }
        return isCheckRoomSatisfied(activityId, participantId);
    }

    private void notifyTurnLightsOn(String executionId, String participantId) {
        notifyParticipantAction(executionId, participantId, "turnLightsOn", "Turn the lights on");
    }

    private boolean isTurnLightsOnSatisfied(String activityId, String participantId) {
        return evaluateGuard("myPlace().light == on", activityId + "#action:turnLightsOn", participantId);
    }

    private void notifyTurnLightsOff(String executionId, String participantId) {
        notifyParticipantAction(executionId, participantId, "turnLightsOff", "Turn the lights off");
    }

    private boolean isTurnLightsOffSatisfied(String activityId, String participantId) {
        return evaluateGuard("myPlace().light == off", activityId + "#action:turnLightsOff", participantId);
    }

    private void notifyCleanRoom(String executionId, String participantId) {
        notifyParticipantAction(executionId, participantId, "cleanRoom", "Clean the room");
    }

    private boolean isCleanRoomSatisfied(String activityId, String participantId) {
        return evaluateGuard("myPlace().state == clean", activityId + "#action:cleanRoom", participantId);
    }

    private void notifyCheckRoom(String executionId, String participantId) {
        notifyParticipantAction(executionId, participantId, "checkRoom", "Check the room");
    }

    private boolean isCheckRoomSatisfied(String activityId, String participantId) {
        return evaluateGuard("myPlace().state != null", activityId + "#action:checkRoom", participantId);
    }

    /**
     * leaveRoom is a system action.
     * Read-only checks must not mutate state; execution clears the current place state on a
     * best-effort basis and always completes the task.
     */
    private boolean leaveRoom(String activityId, String participantId, boolean execute) {
        if (!execute) {
            log.debug("[ENVIRONMENTAL] leaveRoom check-only (no side effects) | activity={} | participant={}",
                    activityId, participantId);
            return true;
        }

        // Execute path — best-effort cleanup, always returns true.
        if (participantId == null || participantId.isBlank()) {
            log.warn("[ENVIRONMENTAL] leaveRoom: participantId missing for activity {} — completing without cleanup", activityId);
            return true;
        }

        String placeRef = participantDataService.getParticipant(participantId)
                .map(p -> p.getPosition())
                .orElse(null);

        if (placeRef != null && !placeRef.isBlank()) {
            String placeId = environmentDataService.resolvePhysicalPlaceId(placeRef).orElse(placeRef);
            environmentDataService.getPhysicalPlace(placeId).ifPresentOrElse(
                    place -> {
                        if (place.getAttributes() != null) {
                            place.getAttributes().put("state", null);
                            place.getAttributes().put("occupied", false);
                            log.info("[ENVIRONMENTAL] leaveRoom executed | activity={} | participant={} | place={} | state=null | occupied=false",
                                    activityId, participantId, place.getId());
                        } else {
                            log.debug("[ENVIRONMENTAL] leaveRoom: place '{}' has no attributes map — skipping | activity={}",
                                    placeId, activityId);
                        }
                    },
                    () -> log.info("[ENVIRONMENTAL] leaveRoom: place '{}' not found — skipping | activity={} | participant={}",
                            placeId, activityId, participantId)
            );
        } else {
            log.info("[ENVIRONMENTAL] leaveRoom: participant '{}' has no current position — skipping state update | activity={}",
                    participantId, activityId);
        }

        return true;
    }

    /**
     * occupyRoom is a system action.
     * Check-only mode has no side effects and always returns true.
     * Execute mode sets occupied=true on the participant's current place.
     */
    private boolean occupyRoom(String activityId, String participantId, boolean execute) {
        if (!execute) {
            log.debug("[ENVIRONMENTAL] occupyRoom check-only (no side effects) | activity={} | participant={}",
                    activityId, participantId);
            return true;
        }

        if (participantId == null || participantId.isBlank()) {
            log.warn("[ENVIRONMENTAL] occupyRoom: participantId missing for activity {} — completing without update", activityId);
            return true;
        }

        String placeRef = participantDataService.getParticipant(participantId)
                .map(p -> p.getPosition())
                .orElse(null);

        if (placeRef != null && !placeRef.isBlank()) {
            String placeId = environmentDataService.resolvePhysicalPlaceId(placeRef).orElse(placeRef);
            environmentDataService.getPhysicalPlace(placeId).ifPresentOrElse(
                    place -> {
                        if (place.getAttributes() != null) {
                            place.getAttributes().put("occupied", true);
                            log.info("[ENVIRONMENTAL] occupyRoom executed | activity={} | participant={} | place={} | occupied=true",
                                    activityId, participantId, place.getId());
                        } else {
                            log.debug("[ENVIRONMENTAL] occupyRoom: place '{}' has no attributes map — skipping | activity={}",
                                    placeId, activityId);
                        }
                    },
                    () -> log.info("[ENVIRONMENTAL] occupyRoom: place '{}' not found — skipping | activity={} | participant={}",
                            placeId, activityId, participantId)
            );
        } else {
            log.info("[ENVIRONMENTAL] occupyRoom: participant '{}' has no current position — skipping | activity={}",
                    participantId, activityId);
        }

        return true;
    }

    private void notifyParticipantAction(String executionId,
                                         String participantId,
                                         String actionKey,
                                         String message) {
        if (executionId == null || executionId.isBlank() || participantId == null || participantId.isBlank()) {
            return;
        }

        String dedupKey = executionId + ":" + actionKey.toLowerCase();
        if (sentActionNotifications.putIfAbsent(dedupKey, Boolean.TRUE) != null) {
            return;
        }

        String businessKey = resolveBusinessKeyForExecution(executionId);
        if (businessKey == null || businessKey.isBlank()) {
            log.warn("[ENVIRONMENTAL] Cannot notify participant '{}' for action '{}' because businessKey is unavailable (execution={})",
                    participantId, actionKey, executionId);
            return;
        }

        String userId = userParticipantMappingService.getUserForParticipant(businessKey, participantId);
        if (userId == null || userId.isBlank()) {
            log.info("[ENVIRONMENTAL] Participant '{}' has no mapped user for businessKey '{}' (action='{}')",
                    participantId, businessKey, actionKey);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", executionId);
        payload.put("participantId", participantId);
        payload.put("action", actionKey);
        payload.put("message", message);

        webSocketSessionManager.broadcastToUser(
                userId,
                GpsResponse.success("ACTION_REQUIRED", message, payload),
                null
        );

        log.info("[ENVIRONMENTAL] Sent action notification | userId={} | participantId={} | action={} | execution={}",
                userId, participantId, actionKey, executionId);
    }

    private String resolveBusinessKeyForExecution(String executionId) {
        try {
            Execution execution = runtimeService.createExecutionQuery()
                    .executionId(executionId)
                    .singleResult();
            if (execution == null || execution.getProcessInstanceId() == null) {
                return null;
            }

            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(execution.getProcessInstanceId())
                    .singleResult();
            return processInstance != null ? processInstance.getBusinessKey() : null;
        } catch (Exception e) {
            log.warn("[ENVIRONMENTAL] Failed to resolve businessKey for execution {}: {}", executionId, e.getMessage());
            return null;
        }
    }

    private void clearNotificationMarkersForExecution(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        String prefix = executionId + ":";
        for (String key : new HashSet<>(sentActionNotifications.keySet())) {
            if (key.startsWith(prefix)) {
                sentActionNotifications.remove(key);
            }
        }
    }

    private String toHumanReadableAction(String action) {
        if (action == null || action.isBlank()) {
            return "Action not specified";
        }

        return switch (action.trim().toLowerCase()) {
            case "turnlightson" -> "Turn the lights on";
            case "turnlightsoff" -> "Turn the lights off";
            case "cleanroom" -> "Clean the room";
            case "checkroom" -> "Check the room";
            case "leaveroom" -> "Leave the room";
            case "occupyroom" -> "Occupy the room";
            default -> "Execute action: " + action;
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

        boolean actionSatisfied = evaluateActionGuard(
                action,
                activityId,
                taskInfo.participantId(),
                executionId,
                true
        );
        if (actionSatisfied) {
            actionTimerStartByExecutionId.remove(executionId);
            clearNotificationMarkersForExecution(executionId);
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
