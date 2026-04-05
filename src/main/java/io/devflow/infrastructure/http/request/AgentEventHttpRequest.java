package io.devflow.infrastructure.http.request;

import io.devflow.domain.agent.AgentEventType;
import io.devflow.domain.workflow.BlockerType;
import io.devflow.domain.workflow.RequestedFrom;
import io.devflow.domain.workflow.ResumeTrigger;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentEventHttpRequest(
    @NotBlank String eventId,
    @NotNull UUID workflowId,
    @NotNull UUID agentRunId,
    @NotNull AgentEventType type,
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
