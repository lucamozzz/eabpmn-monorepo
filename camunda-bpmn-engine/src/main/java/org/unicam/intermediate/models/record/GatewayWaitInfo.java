package org.unicam.intermediate.models.record;

import java.util.List;

public record GatewayWaitInfo(
        String executionId,
        String gatewayId,
        String processDefinitionId,
        List<String> outgoingFlowIds
) {
}
