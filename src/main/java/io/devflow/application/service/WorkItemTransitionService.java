package io.devflow.application.service;

import io.devflow.application.command.ticketing.TransitionWorkItemCommand;
import io.devflow.application.port.ticketing.TicketingPort;
import io.devflow.domain.ticketing.WorkItemTransitionTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkItemTransitionService {

    private static final Logger LOG = Logger.getLogger(WorkItemTransitionService.class);

    @Inject
    TicketingPort ticketingPort;

    public void transitionDirect(String workItemSystem, String workItemKey, WorkItemTransitionTarget target, String reasonCode) {
        LOG.infof(
            "Requesting ticket transition for %s:%s to %s (%s)",
            workItemSystem,
            workItemKey,
            target,
            reasonCode == null || reasonCode.isBlank() ? "no-reason" : reasonCode
        );
        ticketingPort.transition(new TransitionWorkItemCommand(workItemSystem, workItemKey, target, reasonCode));
    }
}
