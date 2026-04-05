package io.devflow.application.command.agent;

import io.devflow.domain.agent.AgentEventType;
import io.devflow.domain.workflow.BlockerType;
import io.devflow.domain.workflow.RequestedFrom;
import io.devflow.domain.workflow.ResumeTrigger;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReceiveAgentEventCommand(
    String eventId,
    UUID workflowId,
    UUID agentRunId,
    AgentEventType type,
    Instant occurredAt,
    String providerRunRef,
    String summary,
    BlockerType blockerType,
    String reasonCode,
    RequestedFrom requestedFrom,
    ResumeTrigger resumeTrigger,
    String suggestedComment,
    Map<String, Object> artifacts,
    Map<String, Object> details
) {
}
