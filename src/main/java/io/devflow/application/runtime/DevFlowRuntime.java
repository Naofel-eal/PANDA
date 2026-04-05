package io.devflow.application.runtime;

import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.workflow.WorkflowPhase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DevFlowRuntime {

    private static final Logger LOG = Logger.getLogger(DevFlowRuntime.class);

    private volatile RunContext currentRun;

    public boolean isBusy() {
        return currentRun != null;
    }

    /**
     * Returns {@code true} when a run is active and has been running longer than {@code maxDuration}.
     */
    public boolean isStale(Duration maxDuration) {
        RunContext snapshot = currentRun;
        return snapshot != null
            && Duration.between(snapshot.startedAt(), Instant.now()).compareTo(maxDuration) > 0;
    }

    public RunContext current() {
        return currentRun;
    }

    public synchronized RunContext startRun(
        UUID workflowId,
        UUID agentRunId,
        String ticketSystem,
        String ticketKey,
        WorkflowPhase phase,
        String objective
    ) {
        if (currentRun != null) {
            throw new IllegalStateException(
                "Cannot start run for " + ticketKey + ": already running for " + currentRun.ticketKey()
            );
        }
        RunContext context = new RunContext(
            workflowId,
            agentRunId,
            ticketSystem,
            ticketKey,
            phase,
            objective,
            Instant.now(),
            new ArrayList<>()
        );
        this.currentRun = context;
        LOG.infof(
            "Started run context: workflow=%s, agentRun=%s, ticket=%s, phase=%s",
            workflowId, agentRunId, ticketKey, phase
        );
        return context;
    }

    public synchronized void clearRun() {
        if (currentRun != null) {
            LOG.infof(
                "Cleared run context: workflow=%s, agentRun=%s, ticket=%s, phase=%s",
                currentRun.workflowId(), currentRun.agentRunId(), currentRun.ticketKey(), currentRun.phase()
            );
        }
        this.currentRun = null;
    }

    public synchronized void clearRunIfMatches(UUID agentRunId) {
        if (currentRun != null && currentRun.agentRunId().equals(agentRunId)) {
            clearRun();
        }
    }

    public synchronized RunContext replacePhase(WorkflowPhase newPhase, UUID newAgentRunId) {
        if (currentRun == null) {
            throw new IllegalStateException("No active run to replace phase on");
        }
        RunContext replaced = new RunContext(
            currentRun.workflowId(),
            newAgentRunId,
            currentRun.ticketSystem(),
            currentRun.ticketKey(),
            newPhase,
            currentRun.objective(),
            currentRun.startedAt(),
            currentRun.publishedPRs()
        );
        this.currentRun = replaced;
        LOG.infof(
            "Replaced run phase: ticket=%s, oldPhase=%s, newPhase=%s, newAgentRun=%s",
            currentRun.ticketKey(), currentRun.phase(), newPhase, newAgentRunId
        );
        return replaced;
    }

    public synchronized void addPublishedPR(CodeChangeRef codeChange) {
        if (currentRun != null) {
            currentRun.publishedPRs().add(codeChange);
            LOG.infof("Tracked published PR: %s for ticket %s", codeChange.externalId(), currentRun.ticketKey());
        }
    }

    public record RunContext(
        UUID workflowId,
        UUID agentRunId,
        String ticketSystem,
        String ticketKey,
        WorkflowPhase phase,
        String objective,
        Instant startedAt,
        List<CodeChangeRef> publishedPRs
    ) {
    }
}
