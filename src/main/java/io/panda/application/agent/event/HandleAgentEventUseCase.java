package io.panda.application.agent.event;

import io.panda.application.agent.dispatch.DispatchAgentRunUseCase;
import io.panda.application.codehost.port.CodeHostPort;
import io.panda.application.codehost.publish.PublishCodeChangesUseCase;
import io.panda.application.command.ticketing.CommentWorkItemCommand;
import io.panda.application.command.ticketing.TransitionWorkItemCommand;
import io.panda.application.command.workspace.ResetWorkspaceCommand;
import io.panda.application.ticketing.port.TicketingPort;
import io.panda.application.workflow.port.WorkflowHolder;
import io.panda.domain.model.agent.AgentEvent;
import io.panda.domain.model.ticketing.IncomingComment;
import io.panda.domain.model.ticketing.WorkItem;
import io.panda.domain.model.ticketing.WorkItemTransitionTarget;
import io.panda.domain.model.workflow.Workflow;
import io.panda.domain.model.workflow.WorkflowPhase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HandleAgentEventUseCase {

    private static final Logger LOG = Logger.getLogger(HandleAgentEventUseCase.class);

    @Inject
    WorkflowHolder workflowHolder;

    @Inject
    TicketingPort ticketingPort;

    @Inject
    CodeHostPort codeHostPort;

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

        if (isTicketInTerminalStatus(workflow)) {
            LOG.infof("Ticket %s is already in a terminal status (Done); abandoning workflow and ignoring event %s",
                workflow.ticketKey(), event.eventId());
            workflowHolder.clear();
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

    private boolean isTicketInTerminalStatus(Workflow workflow) {
        try {
            return ticketingPort.isTerminalStatus(workflow.ticketSystem(), workflow.ticketKey());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to check terminal status for ticket %s; proceeding with event handling",
                workflow.ticketKey());
            return false;
        }
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
        resetWorkspaceSafely(workflow);
    }

    private void handleCompleted(Workflow workflow, AgentEvent event) {
        LOG.infof("Agent run %s completed for ticket %s in phase %s",
            workflow.agentRunId(), workflow.ticketKey(), workflow.phase());

        if (workflow.phase() == WorkflowPhase.INFORMATION_COLLECTION) {
            if (AgentEvent.REASON_ALREADY_IMPLEMENTED.equalsIgnoreCase(event.reasonCode())) {
                LOG.infof("Ticket %s is already implemented — transitioning to validation", workflow.ticketKey());
                moveTicket(workflow, WorkItemTransitionTarget.TO_VALIDATE, AgentEvent.REASON_ALREADY_IMPLEMENTED);
                postComment(workflow, event.summary(), AgentEvent.REASON_ALREADY_IMPLEMENTED);
                workflowHolder.clear();
            } else {
                chainToImplementation(workflow, event);
            }
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
        LOG.warnf("Agent run %s failed for ticket %s: %s — summary: %s",
            workflow.agentRunId(), workflow.ticketKey(), reasonCode,
            event.summary() != null ? event.summary().substring(0, Math.min(event.summary().length(), 200)) : "null");

        moveTicket(workflow, WorkItemTransitionTarget.BLOCKED, reasonCode);
        postComment(workflow, event.buildFailureComment(), reasonCode);
        workflowHolder.clear();
        resetWorkspaceSafely(workflow);
    }

    private void handleCancelled(Workflow workflow) {
        LOG.infof("Agent run %s cancelled for ticket %s",
            workflow.agentRunId(), workflow.ticketKey());
        workflowHolder.clear();
        resetWorkspaceSafely(workflow);
    }

    private void chainToImplementation(Workflow workflow, AgentEvent event) {
        LOG.infof("Ticket %s completed info collection, chaining to implementation", workflow.ticketKey());
        UUID newAgentRunId = UUID.randomUUID();
        Workflow nextWorkflow = workflowHolder.replacePhase(WorkflowPhase.IMPLEMENTATION, newAgentRunId);

        Map<String, Object> snapshot = buildChainSnapshot(nextWorkflow, event);
        String objective = "Implement work item " + workflow.ticketKey();

        try {
            dispatchAgentRunUseCase.execute(nextWorkflow, objective, snapshot);
            LOG.infof("Chained to implementation phase for ticket %s, new agentRunId=%s",
                workflow.ticketKey(), newAgentRunId);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to dispatch implementation phase for ticket %s", workflow.ticketKey());
            handleChainFailure(workflow, e);
        }
    }

    private void handleChainFailure(Workflow workflow, RuntimeException cause) {
        workflowHolder.clear();
        moveTicket(workflow, WorkItemTransitionTarget.BLOCKED, "CHAIN_DISPATCH_FAILED");
        postComment(workflow,
            "PANDA completed information collection but failed to start the implementation phase "
                + "due to a dispatch error: " + cause.getMessage()
                + "\n\nPANDA marked this ticket as blocked until the issue is resolved.",
            "CHAIN_DISPATCH_FAILED"
        );
    }

    private void resetWorkspaceSafely(Workflow workflow) {
        try {
            codeHostPort.resetWorkspace(new ResetWorkspaceCommand(workflow.workflowId()));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to reset workspace for workflow %s (ticket %s); will be cleaned on next prepare",
                workflow.workflowId(), workflow.ticketKey());
        }
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
