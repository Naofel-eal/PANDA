package io.nud.domain.model.workflow;

import io.nud.domain.model.codehost.CodeChangeRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class Workflow {

    private final UUID workflowId;
    private final UUID agentRunId;
    private final String ticketSystem;
    private final String ticketKey;
    private final WorkflowPhase phase;
    private final String objective;
    private final Instant startedAt;
    private final List<CodeChangeRef> publishedPRs;

    private Workflow(
        UUID workflowId,
        UUID agentRunId,
        String ticketSystem,
        String ticketKey,
        WorkflowPhase phase,
        String objective,
        Instant startedAt,
        List<CodeChangeRef> publishedPRs
    ) {
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        this.ticketSystem = Objects.requireNonNull(ticketSystem, "ticketSystem");
        this.ticketKey = Objects.requireNonNull(ticketKey, "ticketKey");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.objective = objective;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.publishedPRs = new ArrayList<>(publishedPRs);
    }

    public static Workflow start(
        UUID workflowId,
        UUID agentRunId,
        String ticketSystem,
        String ticketKey,
        WorkflowPhase phase,
        String objective
    ) {
        return new Workflow(workflowId, agentRunId, ticketSystem, ticketKey, phase, objective, Instant.now(), List.of());
    }

    public Workflow chainToPhase(WorkflowPhase newPhase, UUID newAgentRunId) {
        return new Workflow(workflowId, newAgentRunId, ticketSystem, ticketKey, newPhase, objective, startedAt, publishedPRs);
    }

    public void addPublishedPR(CodeChangeRef codeChange) {
        publishedPRs.add(Objects.requireNonNull(codeChange, "codeChange"));
    }

    public boolean isStale(Duration maxDuration) {
        return Duration.between(startedAt, Instant.now()).compareTo(maxDuration) > 0;
    }

    public boolean belongsTo(UUID agentRunId) {
        return this.agentRunId.equals(agentRunId);
    }
}
