package io.devflow.domain.workflow;

import io.devflow.domain.ticketing.WorkItemRef;
import java.time.Instant;
import java.util.UUID;

public record WorkflowSnapshot(
    UUID id,
    WorkItemRef workItem,
    WorkflowPhase phase,
    WorkflowStatus status,
    UUID currentBlockerId,
    Instant createdAt,
    Instant updatedAt
) {
}
