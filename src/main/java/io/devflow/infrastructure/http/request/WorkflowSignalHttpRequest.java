package io.devflow.infrastructure.http.request;

import io.devflow.domain.workflow.WorkflowSignalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record WorkflowSignalHttpRequest(
    @NotNull WorkflowSignalType type,
    @NotBlank String sourceSystem,
    String sourceEventId,
    UUID workflowId,
    Instant occurredAt,
    @Valid WorkItemHttpPayload workItem,
    @Valid CommentHttpPayload comment,
    @Valid CodeChangeHttpPayload codeChange,
    @Valid DeploymentHttpPayload deployment,
    @Valid BusinessValidationHttpPayload businessValidation
) {
}
