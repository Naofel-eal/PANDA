package io.devflow.infrastructure.http.response;

import io.devflow.domain.workflow.WorkflowPhase;
import io.devflow.domain.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkflowHttpResponse(
    UUID id,
    String workItemSystem,
    String workItemKey,
    WorkflowPhase phase,
    WorkflowStatus status,
    UUID currentBlockerId,
    Instant createdAt,
    Instant updatedAt
) {
}
