package io.panda.application.workflow;

import io.panda.application.workflow.port.WorkflowHolder;
import io.panda.application.workflow.port.WorkflowRepository;
import io.panda.domain.model.codehost.CodeChangeRef;
import io.panda.domain.model.workflow.Workflow;
import io.panda.domain.model.workflow.WorkflowPhase;
import io.panda.domain.model.workflow.WorkflowStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class InMemoryWorkflowHolder implements WorkflowHolder {

    private static final Logger LOG = Logger.getLogger(InMemoryWorkflowHolder.class);

    @Inject
    WorkflowRepository workflowRepository;

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
        persist(workflow);
        LOG.infof(
            "Started workflow: workflow=%s, agentRun=%s, ticket=%s, phase=%s",
            workflow.workflowId(), workflow.agentRunId(), workflow.ticketKey(), workflow.phase()
        );
        return workflow;
    }

    @Override
    public synchronized void clear() {
        if (currentWorkflow != null) {
            Workflow terminated = currentWorkflow.terminate(WorkflowStatus.COMPLETED);
            persist(terminated);
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
        persist(replaced);
        return replaced;
    }

    @Override
    public synchronized void addPublishedPR(CodeChangeRef codeChange) {
        if (currentWorkflow != null) {
            currentWorkflow.addPublishedPR(codeChange);
            persist(currentWorkflow);
            LOG.infof("Tracked published PR: %s for ticket %s", codeChange.externalId(), currentWorkflow.ticketKey());
        }
    }

    public synchronized void rehydrate() {
        if (workflowRepository == null) {
            return;
        }
        var activeWorkflows = workflowRepository.findByStatus(WorkflowStatus.ACTIVE);
        if (activeWorkflows.isEmpty()) {
            LOG.info("No active workflow found on disk — starting fresh");
            return;
        }
        Workflow restored = activeWorkflows.getFirst();
        this.currentWorkflow = restored;
        LOG.infof(
            "Rehydrated workflow from disk: workflow=%s, ticket=%s, phase=%s",
            restored.workflowId(), restored.ticketKey(), restored.phase()
        );
    }

    private void persist(Workflow workflow) {
        if (workflowRepository != null) {
            workflowRepository.save(workflow);
        }
    }
}
