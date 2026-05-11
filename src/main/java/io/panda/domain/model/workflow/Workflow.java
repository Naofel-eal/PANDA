package io.panda.domain.model.workflow;

import io.panda.domain.model.codehost.CodeChangeRef;
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
    private final WorkflowStatus status;
    @Getter(lombok.AccessLevel.NONE)
    private final List<CodeChangeRef> publishedPRs;
    @Getter(lombok.AccessLevel.NONE)
    private final List<WorkflowTransition> transitions;

    private Workflow(
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
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        this.ticketSystem = Objects.requireNonNull(ticketSystem, "ticketSystem");
        this.ticketKey = Objects.requireNonNull(ticketKey, "ticketKey");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.objective = objective;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.publishedPRs = new ArrayList<>(publishedPRs);
        this.transitions = new ArrayList<>(transitions);
    }

    public static Workflow start(
        UUID workflowId,
        UUID agentRunId,
        String ticketSystem,
        String ticketKey,
        WorkflowPhase phase,
        String objective
    ) {
        List<WorkflowTransition> initialTransitions = List.of(
            new WorkflowTransition(phase, agentRunId, Instant.now())
        );
        return new Workflow(
            workflowId, agentRunId, ticketSystem, ticketKey, phase, objective,
            Instant.now(), WorkflowStatus.ACTIVE, List.of(), initialTransitions
        );
    }

    public static Workflow reconstitute(
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
        return new Workflow(
            workflowId, agentRunId, ticketSystem, ticketKey, phase, objective,
            startedAt, status,
            publishedPRs != null ? publishedPRs : List.of(),
            transitions != null ? transitions : List.of()
        );
    }

    public List<CodeChangeRef> publishedPRs() {
        return List.copyOf(publishedPRs);
    }

    public List<WorkflowTransition> transitions() {
        return List.copyOf(transitions);
    }

    public Workflow chainToPhase(WorkflowPhase newPhase, UUID newAgentRunId) {
        Workflow chained = new Workflow(
            workflowId, newAgentRunId, ticketSystem, ticketKey, newPhase, objective,
            startedAt, status, publishedPRs, transitions
        );
        chained.transitions.add(new WorkflowTransition(newPhase, newAgentRunId, Instant.now()));
        return chained;
    }

    public void addPublishedPR(CodeChangeRef codeChange) {
        publishedPRs.add(Objects.requireNonNull(codeChange, "codeChange"));
    }

    public Workflow terminate(WorkflowStatus terminalStatus) {
        return new Workflow(
            workflowId, agentRunId, ticketSystem, ticketKey, phase, objective,
            startedAt, terminalStatus, publishedPRs, transitions
        );
    }

    public boolean isActive() {
        return status == WorkflowStatus.ACTIVE;
    }

    public boolean isStale(Duration maxDuration) {
        return Duration.between(startedAt, Instant.now()).compareTo(maxDuration) > 0;
    }

    public boolean belongsTo(UUID agentRunId) {
        return this.agentRunId.equals(agentRunId);
    }
}
