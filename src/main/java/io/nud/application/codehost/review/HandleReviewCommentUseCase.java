package io.nud.application.codehost.review;

import io.nud.application.agent.dispatch.DispatchAgentRunUseCase;
import io.nud.application.ticketing.port.TicketingPort;
import io.nud.application.workflow.port.WorkflowHolder;
import io.nud.domain.model.codehost.CodeChangeRef;
import io.nud.domain.model.ticketing.IncomingComment;
import io.nud.domain.model.ticketing.WorkItem;
import java.util.List;
import io.nud.domain.model.workflow.Workflow;
import io.nud.domain.model.workflow.WorkflowPhase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HandleReviewCommentUseCase {

    private static final Logger LOG = Logger.getLogger(HandleReviewCommentUseCase.class);

    @Inject
    WorkflowHolder workflowHolder;

    @Inject
    DispatchAgentRunUseCase dispatchAgentRunUseCase;

    @Inject
    TicketingPort ticketingPort;

    public void execute(Command command) {
        UUID workflowId = UUID.randomUUID();
        UUID agentRunId = UUID.randomUUID();
        String objective = "Address " + command.reviewComments().size() + " review comment(s) on PR "
            + command.codeChange().externalId() + " for ticket " + command.ticketKey();

        Workflow workflow = Workflow.start(
            workflowId, agentRunId, command.ticketSystem(), command.ticketKey(),
            WorkflowPhase.TECHNICAL_VALIDATION, objective
        );
        workflowHolder.start(workflow);

        Map<String, Object> snapshot = buildSnapshot(workflowId, command);

        try {
            LOG.infof("Dispatching agent run for %d review comment(s) on ticket %s", command.reviewComments().size(), command.ticketKey());
            dispatchAgentRunUseCase.execute(workflow, objective, snapshot);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to dispatch agent run for review comment on ticket %s", command.ticketKey());
            workflowHolder.clear();
        }
    }

    private Map<String, Object> buildSnapshot(UUID workflowId, Command command) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowId", workflowId.toString());
        snapshot.put("workItemSystem", command.ticketSystem());
        snapshot.put("workItemKey", command.ticketKey());
        snapshot.put("codeChange", command.codeChange());
        snapshot.put("reviewComments", command.reviewComments());
        snapshot.put("repositories", java.util.List.of(command.repository()));

        WorkItem workItem = safeLoadWorkItem(command);
        if (workItem != null) {
            snapshot.put("workItem", workItem);
        }
        return snapshot;
    }

    private WorkItem safeLoadWorkItem(Command command) {
        try {
            return ticketingPort.loadWorkItem(command.ticketSystem(), command.ticketKey()).orElse(null);
        } catch (RuntimeException e) {
            LOG.debugf("Unable to load work item %s (non-critical)", command.ticketKey());
            return null;
        }
    }

    public record Command(
        String ticketSystem,
        String ticketKey,
        String repository,
        CodeChangeRef codeChange,
        List<IncomingComment> reviewComments
    ) {}
}
