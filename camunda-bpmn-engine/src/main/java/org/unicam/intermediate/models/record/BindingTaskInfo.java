package org.unicam.intermediate.models.record;

public record BindingTaskInfo(
        String businessKey,
        String participantId,
        String targetParticipantId,
        String executionId
) {
}
