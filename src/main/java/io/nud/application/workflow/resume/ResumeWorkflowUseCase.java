package io.nud.application.workflow.resume;

import io.nud.application.agent.dispatch.DispatchAgentRunUseCase;
import io.nud.application.workflow.port.WorkflowHolder;
import io.nud.domain.model.ticketing.IncomingComment;
import io.nud.domain.model.ticketing.WorkItem;
import io.nud.domain.model.workflow.Workflow;
import io.nud.domain.model.workflow.WorkflowPhase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ResumeWorkflowUseCase {

    private static final Logger LOG = Logger.getLogger(ResumeWorkflowUseCase.class);

    @Inject
    WorkflowHolder workflowHolder;

    @Inject
    DispatchAgentRunUseCase dispatchAgentRunUseCase;

    public void execute(String ticketSystem, WorkItem workItem, List<IncomingComment> comments, WorkflowPhase phase) {
        UUID workflowId = UUID.randomUUID();
        UUID agentRunId = UUID.randomUUID();
        String objective = phase == WorkflowPhase.INFORMATION_COLLECTION
            ? "Analyze work item " + workItem.key() + " and prepare an implementation plan"
            : "Implement work item " + workItem.key();

        Workflow workflow = Workflow.start(
            workflowId, agentRunId, ticketSystem, workItem.key(), phase, objective
        );
        workflowHolder.start(workflow);

        Map<String, Object> snapshot = buildSnapshot(workflowId, ticketSystem, workItem, comments);

        try {
            LOG.infof("Resuming workflow for ticket %s in phase %s", workItem.key(), phase);
            dispatchAgentRunUseCase.execute(workflow, objective, snapshot);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to dispatch agent run for resumed ticket %s", workItem.key());
            workflowHolder.clear();
        }
    }

    private Map<String, Object> buildSnapshot(
        UUID workflowId, String ticketSystem, WorkItem workItem, List<IncomingComment> comments
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowId", workflowId.toString());
        snapshot.put("workItemSystem", ticketSystem);
        snapshot.put("workItemKey", workItem.key());
        snapshot.put("workItem", workItem);
        if (!comments.isEmpty()) {
            snapshot.put("workItemComments", comments);
        }
        return snapshot;
    }
}
