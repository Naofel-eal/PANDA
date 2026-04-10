package io.nud.infrastructure.http.request;

import io.nud.domain.model.agent.AgentEventType;
import io.nud.domain.model.workflow.BlockerType;
import io.nud.domain.model.workflow.RequestedFrom;
import io.nud.domain.model.workflow.ResumeTrigger;
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
