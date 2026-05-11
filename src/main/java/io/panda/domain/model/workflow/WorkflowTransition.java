package io.panda.domain.model.workflow;

import java.time.Instant;
import java.util.UUID;

public record WorkflowTransition(
    WorkflowPhase phase,
    UUID agentRunId,
    Instant occurredAt
) {
}
