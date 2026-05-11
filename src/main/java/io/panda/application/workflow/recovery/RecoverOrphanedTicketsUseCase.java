package io.panda.application.workflow.recovery;

import io.panda.application.command.ticketing.CommentWorkItemCommand;
import io.panda.application.command.ticketing.TransitionWorkItemCommand;
import io.panda.application.ticketing.port.TicketingPort;
import io.panda.domain.model.ticketing.WorkItem;
import io.panda.domain.model.ticketing.WorkItemTransitionTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RecoverOrphanedTicketsUseCase {

    private static final Logger LOG = Logger.getLogger(RecoverOrphanedTicketsUseCase.class);

    private static final String RECOVERY_REASON = "ORCHESTRATOR_RESTART_RECOVERY";
    private static final String RECOVERY_COMMENT =
        "PANDA restarted and lost track of this ticket's active run. "
            + "The ticket has been moved back to the queue and will be picked up automatically at the next poll cycle.";

    @Inject
    TicketingPort ticketingPort;

    public void execute(String ticketSystem, List<WorkItem> orphanedTickets) {
        if (orphanedTickets.isEmpty()) {
            return;
        }

        LOG.infof("Recovering %d orphaned ticket(s) stuck in 'In Progress': %s",
            orphanedTickets.size(),
            orphanedTickets.stream().map(WorkItem::key).toList());

        for (WorkItem workItem : orphanedTickets) {
            recover(ticketSystem, workItem);
        }
    }

    private void recover(String ticketSystem, WorkItem workItem) {
        try {
            ticketingPort.transition(new TransitionWorkItemCommand(
                ticketSystem, workItem.key(), WorkItemTransitionTarget.TODO, RECOVERY_REASON));
            ticketingPort.comment(new CommentWorkItemCommand(
                ticketSystem, workItem.key(), RECOVERY_COMMENT, RECOVERY_REASON));
            LOG.infof("Recovered orphaned ticket %s — moved to 'To Do'", workItem.key());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to recover orphaned ticket %s, will retry on next restart", workItem.key());
        }
    }
}
