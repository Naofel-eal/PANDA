package io.panda.infrastructure.workflow;

import io.panda.domain.model.codehost.CodeChangeRef;
import io.panda.domain.model.workflow.WorkflowPhase;
import io.panda.domain.model.workflow.WorkflowStatus;
import io.panda.domain.model.workflow.WorkflowTransition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowRecord(
    UUID workflowId,
    UUID agentRunId,
    String ticketSystem,
    String ticketKey,
    WorkflowPhase phase,
    String objective,
    Instant startedAt,
    WorkflowStatus status,
    List<CodeChangeRef> publishedPRs,
    List<WorkflowTransition> transitions
) {
}
