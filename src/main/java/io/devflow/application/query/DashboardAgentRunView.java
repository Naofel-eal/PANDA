package io.devflow.application.query;

import io.devflow.domain.agent.AgentRunStatus;
import io.devflow.domain.workflow.WorkflowPhase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DashboardAgentRunView(
    UUID id,
    WorkflowPhase phase,
    AgentRunStatus status,
    Instant createdAt,
    Instant startedAt,
    Instant endedAt,
    String providerRunRef,
    List<DashboardRunEventView> events
) {

    public DashboardAgentRunView {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
