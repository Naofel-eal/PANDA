package io.devflow.application.service;

import io.devflow.application.command.ticketing.TransitionWorkItemCommand;
import io.devflow.application.port.ticketing.TicketingPort;
import io.devflow.domain.ticketing.WorkItemTransitionTarget;
import io.devflow.domain.workflow.Workflow;
import io.devflow.domain.workflow.WorkflowAuditType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkItemTransitionService {

    private static final Logger LOG = Logger.getLogger(WorkItemTransitionService.class);

    @Inject
    TicketingPort ticketingPort;

    @Inject
    WorkflowAuditService workflowAuditService;

    public void transition(Workflow workflow, WorkItemTransitionTarget target, String reasonCode) {
        LOG.infof(
            "Requesting ticket transition for %s:%s to %s (%s)",
            workflow.workItemSystem(),
            workflow.workItemKey(),
            target,
            reasonCode == null || reasonCode.isBlank() ? "no-reason" : reasonCode
        );
        workflowAuditService.record(
            workflow.id(),
            WorkflowAuditType.WORK_ITEM_TRANSITION_REQUESTED,
            Map.of(
                "workItemSystem", workflow.workItemSystem(),
                "workItemKey", workflow.workItemKey(),
                "target", target.name(),
                "reasonCode", reasonCode == null ? "" : reasonCode
            ),
            workflow.workItemSystem(),
            null
        );

        ticketingPort.transition(new TransitionWorkItemCommand(
            workflow.workItemSystem(),
            workflow.workItemKey(),
            target,
            reasonCode
        ));
    }
}
