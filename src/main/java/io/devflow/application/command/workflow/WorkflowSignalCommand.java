package io.devflow.application.command.workflow;

import io.devflow.domain.workflow.WorkflowSignalType;
import io.devflow.domain.validation.BusinessValidationReport;
import io.devflow.domain.validation.DeploymentInfo;
import io.devflow.domain.ticketing.IncomingComment;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.ticketing.WorkItem;
import java.time.Instant;
import java.util.UUID;

public record WorkflowSignalCommand(
    WorkflowSignalType type,
    String sourceSystem,
    String sourceEventId,
    UUID workflowId,
    Instant occurredAt,
    WorkItem workItem,
    IncomingComment comment,
    CodeChangeRef codeChange,
    DeploymentInfo deployment,
    BusinessValidationReport businessValidation
) {
}
