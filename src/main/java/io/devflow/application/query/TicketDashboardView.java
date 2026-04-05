package io.devflow.application.query;

import io.devflow.domain.workflow.WorkflowPhase;
import io.devflow.domain.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketDashboardView(
    UUID workflowId,
    String workItemSystem,
    String workItemKey,
    String title,
    String description,
    String url,
    WorkflowPhase phase,
    WorkflowStatus status,
    Instant createdAt,
    Instant updatedAt,
    List<String> repositories,
    DashboardBlockerView currentBlocker,
    DashboardAgentRunView currentRun,
    List<DashboardAgentRunView> runs,
    List<DashboardCodeChangeView> codeChanges,
    List<DashboardTimelineEntryView> timeline
) {

    public TicketDashboardView {
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        runs = runs == null ? List.of() : List.copyOf(runs);
        codeChanges = codeChanges == null ? List.of() : List.copyOf(codeChanges);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }
}
