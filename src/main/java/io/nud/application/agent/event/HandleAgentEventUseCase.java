package io.nud.application.agent.event;

import io.nud.application.agent.dispatch.DispatchAgentRunUseCase;
import io.nud.application.codehost.publish.PublishCodeChangesUseCase;
import io.nud.application.command.ticketing.CommentWorkItemCommand;
import io.nud.application.command.ticketing.TransitionWorkItemCommand;
import io.nud.application.ticketing.port.TicketingPort;
import io.nud.application.workflow.port.WorkflowHolder;
import io.nud.domain.model.agent.AgentEvent;
import io.nud.domain.model.ticketing.IncomingComment;
import io.nud.domain.model.ticketing.WorkItem;
import io.nud.domain.model.ticketing.WorkItemTransitionTarget;
import io.nud.domain.model.workflow.Workflow;
import io.nud.domain.model.workflow.WorkflowPhase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HandleAgentEventUseCase {

    private static final Logger LOG = Logger.getLogger(HandleAgentEventUseCase.class);
    private static final int CHAIN_DISPATCH_DELAY_MS = 3_000;

    @Inject
    WorkflowHolder workflowHolder;

    @Inject
    TicketingPort ticketingPort;

    @Inject
    DispatchAgentRunUseCase dispatchAgentRunUseCase;

    @Inject
    PublishCodeChangesUseCase publishCodeChangesUseCase;

    public boolean execute(AgentEvent event) {
        Workflow workflow = workflowHolder.current();
        if (workflow == null) {
            LOG.warnf("Received agent event %s but no active workflow; ignoring", event.eventId());
            return false;
        }
        if (!workflow.belongsTo(event.agentRunId())) {
            LOG.warnf(
                "Received agent event %s for agentRunId %s but current workflow is %s; ignoring",
                event.eventId(), event.agentRunId(), workflow.agentRunId()
            );
            return false;
        }

        LOG.infof(
            "Received agent event %s of type %s for ticket %s in phase %s",
            event.eventId(), event.type(), workflow.ticketKey(), workflow.phase()
        );

        switch (event.type()) {
            case RUN_STARTED -> handleRunStarted(workflow);
            case PROGRESS_REPORTED -> handleProgress(workflow);
            case INPUT_REQUIRED -> handleInputRequired(workflow, event);
            case COMPLETED -> handleCompleted(workflow, event);
            case FAILED -> handleFailed(workflow, event);
            case CANCELLED -> handleCancelled(workflow);
        }
        return true;
    }

    private void handleRunStarted(Workflow workflow) {
        LOG.infof("Agent run %s started for ticket %s in phase %s",
            workflow.agentRunId(), workflow.ticketKey(), workflow.phase());
        moveTicket(workflow, WorkItemTransitionTarget.IN_PROGRESS, "AGENT_RUN_STARTED");
    }

    private void handleProgress(Workflow workflow) {
        LOG.infof("Agent run %s progress reported for ticket %s",
            workflow.agentRunId(), workflow.ticketKey());
    }

    private void handleInputRequired(Workflow workflow, AgentEvent event) {
        String reasonCode = event.normalizedReasonCode(workflow.phase());
        LOG.infof("Agent run %s requires input for ticket %s: %s",
            workflow.agentRunId(), workflow.ticketKey(), reasonCode);

        moveTicket(workflow, WorkItemTransitionTarget.BLOCKED, reasonCode);
        String comment = event.normalizedSuggestedComment();
        if (comment != null && !comment.isBlank()) {
            postComment(workflow, comment, reasonCode);
        }
        workflowHolder.clear();
    }

    private void handleCompleted(Workflow workflow, AgentEvent event) {
        LOG.infof("Agent run %s completed for ticket %s in phase %s",
            workflow.agentRunId(), workflow.ticketKey(), workflow.phase());

        if (workflow.phase() == WorkflowPhase.INFORMATION_COLLECTION) {
            chainToImplementation(workflow, event);
        } else if (workflow.phase() == WorkflowPhase.IMPLEMENTATION
            || workflow.phase() == WorkflowPhase.BUSINESS_VALIDATION) {
            publishCodeChangesUseCase.execute(workflow, event, false);
        } else if (workflow.phase() == WorkflowPhase.TECHNICAL_VALIDATION) {
            publishCodeChangesUseCase.execute(workflow, event, false);
        } else {
            workflowHolder.clear();
        }
    }

    private void handleFailed(Workflow workflow, AgentEvent event) {
        String reasonCode = event.normalizedReasonCode(workflow.phase());
        LOG.warnf("Agent run %s failed for ticket %s: %s",
            workflow.agentRunId(), workflow.ticketKey(), reasonCode);

        moveTicket(workflow, WorkItemTransitionTarget.BLOCKED, reasonCode);
        postComment(workflow, event.buildFailureComment(), reasonCode);
        workflowHolder.clear();
    }

    private void handleCancelled(Workflow workflow) {
        LOG.infof("Agent run %s cancelled for ticket %s",
            workflow.agentRunId(), workflow.ticketKey());
        workflowHolder.clear();
    }

    private void chainToImplementation(Workflow workflow, AgentEvent event) {
        LOG.infof("Ticket %s completed info collection, chaining to implementation", workflow.ticketKey());
        UUID newAgentRunId = UUID.randomUUID();
        Workflow nextWorkflow = workflowHolder.replacePhase(WorkflowPhase.IMPLEMENTATION, newAgentRunId);

        Map<String, Object> snapshot = buildChainSnapshot(nextWorkflow, event);
        String objective = "Implement work item " + workflow.ticketKey();

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(CHAIN_DISPATCH_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.errorf("Chain dispatch interrupted for ticket %s", workflow.ticketKey());
                handleChainFailure(workflow, new RuntimeException("Interrupted while waiting to dispatch implementation", e));
                return;
            }
            try {
                dispatchAgentRunUseCase.execute(nextWorkflow, objective, snapshot);
            } catch (RuntimeException e) {
                LOG.errorf(e, "Failed to dispatch implementation phase for ticket %s", workflow.ticketKey());
                handleChainFailure(workflow, e);
            }
        });
    }

    private void handleChainFailure(Workflow workflow, RuntimeException cause) {
        workflowHolder.clear();
        moveTicket(workflow, WorkItemTransitionTarget.BLOCKED, "CHAIN_DISPATCH_FAILED");
        postComment(workflow,
            "NUD completed information collection but failed to start the implementation phase "
                + "due to a dispatch error: " + cause.getMessage()
                + "\n\nNUD marked this ticket as blocked until the issue is resolved.",
            "CHAIN_DISPATCH_FAILED"
        );
    }

    private Map<String, Object> buildChainSnapshot(Workflow workflow, AgentEvent event) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowId", workflow.workflowId().toString());
        snapshot.put("workItemSystem", workflow.ticketSystem());
        snapshot.put("workItemKey", workflow.ticketKey());
        snapshot.put("previousRunSummary", event.summary() == null ? "" : event.summary());

        WorkItem workItem = safeLoadWorkItem(workflow);
        if (workItem != null) {
            snapshot.put("workItem", workItem);
        }
        List<IncomingComment> comments = safeLoadComments(workflow);
        if (!comments.isEmpty()) {
            snapshot.put("workItemComments", comments);
        }

        return snapshot;
    }

    private void moveTicket(Workflow workflow, WorkItemTransitionTarget target, String reasonCode) {
        try {
            ticketingPort.transition(new TransitionWorkItemCommand(
                workflow.ticketSystem(), workflow.ticketKey(), target, reasonCode));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to transition ticket %s to %s", workflow.ticketKey(), target);
        }
    }

    private void postComment(Workflow workflow, String comment, String reasonCode) {
        try {
            ticketingPort.comment(new CommentWorkItemCommand(
                workflow.ticketSystem(), workflow.ticketKey(), comment, reasonCode));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to post comment to ticket %s", workflow.ticketKey());
        }
    }

    private WorkItem safeLoadWorkItem(Workflow workflow) {
        try {
            return ticketingPort.loadWorkItem(workflow.ticketSystem(), workflow.ticketKey()).orElse(null);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to load work item %s", workflow.ticketKey());
            return null;
        }
    }

    private List<IncomingComment> safeLoadComments(Workflow workflow) {
        try {
            return ticketingPort.listComments(workflow.ticketSystem(), workflow.ticketKey());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to load comments for ticket %s", workflow.ticketKey());
            return List.of();
        }
    }
}
