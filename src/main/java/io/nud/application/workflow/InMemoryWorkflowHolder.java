package io.nud.application.workflow;

import io.nud.application.workflow.port.WorkflowHolder;
import io.nud.domain.model.codehost.CodeChangeRef;
import io.nud.domain.model.workflow.Workflow;
import io.nud.domain.model.workflow.WorkflowPhase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class InMemoryWorkflowHolder implements WorkflowHolder {

    private static final Logger LOG = Logger.getLogger(InMemoryWorkflowHolder.class);

    private volatile Workflow currentWorkflow;

    @Override
    public boolean isBusy() {
        return currentWorkflow != null;
    }

    @Override
    public boolean isStale(Duration maxDuration) {
        Workflow snapshot = currentWorkflow;
        return snapshot != null && snapshot.isStale(maxDuration);
    }

    @Override
    public Workflow current() {
        return currentWorkflow;
    }

    @Override
    public synchronized Workflow start(Workflow workflow) {
        if (currentWorkflow != null) {
            throw new IllegalStateException(
                "Cannot start workflow for " + workflow.ticketKey()
                    + ": already running for " + currentWorkflow.ticketKey()
            );
        }
        this.currentWorkflow = workflow;
        LOG.infof(
            "Started workflow: workflow=%s, agentRun=%s, ticket=%s, phase=%s",
            workflow.workflowId(), workflow.agentRunId(), workflow.ticketKey(), workflow.phase()
        );
        return workflow;
    }

    @Override
    public synchronized void clear() {
        if (currentWorkflow != null) {
            LOG.infof(
                "Cleared workflow: workflow=%s, agentRun=%s, ticket=%s, phase=%s",
                currentWorkflow.workflowId(), currentWorkflow.agentRunId(),
                currentWorkflow.ticketKey(), currentWorkflow.phase()
            );
        }
        this.currentWorkflow = null;
    }

    @Override
    public synchronized void clearIfMatches(UUID agentRunId) {
        if (currentWorkflow != null && currentWorkflow.belongsTo(agentRunId)) {
            clear();
        }
    }

    @Override
    public synchronized Workflow replacePhase(WorkflowPhase newPhase, UUID newAgentRunId) {
        if (currentWorkflow == null) {
            throw new IllegalStateException("No active workflow to replace phase on");
        }
        Workflow replaced = currentWorkflow.chainToPhase(newPhase, newAgentRunId);
        LOG.infof(
            "Replaced workflow phase: ticket=%s, oldPhase=%s, newPhase=%s, newAgentRun=%s",
            currentWorkflow.ticketKey(), currentWorkflow.phase(), newPhase, newAgentRunId
        );
        this.currentWorkflow = replaced;
        return replaced;
    }

    @Override
    public synchronized void addPublishedPR(CodeChangeRef codeChange) {
        if (currentWorkflow != null) {
            currentWorkflow.addPublishedPR(codeChange);
            LOG.infof("Tracked published PR: %s for ticket %s", codeChange.externalId(), currentWorkflow.ticketKey());
        }
    }
}
