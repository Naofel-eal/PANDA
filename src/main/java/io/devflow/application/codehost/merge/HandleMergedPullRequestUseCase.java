package io.devflow.application.codehost.merge;

import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.command.ticketing.TransitionWorkItemCommand;
import io.devflow.application.ticketing.port.TicketingPort;
import io.devflow.domain.model.ticketing.WorkItem;
import io.devflow.domain.model.ticketing.WorkItemTransitionTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HandleMergedPullRequestUseCase {

    private static final Logger LOG = Logger.getLogger(HandleMergedPullRequestUseCase.class);

    @Inject
    TicketingPort ticketingPort;

    public void execute(Command command) {
        LOG.infof("Processing merged PR %s for ticket %s — transitioning to 'To Validate'",
            command.externalId(), command.ticketKey());

        try {
            ticketingPort.transition(new TransitionWorkItemCommand(
                command.ticketSystem(), command.ticketKey(),
                WorkItemTransitionTarget.TO_VALIDATE, "PR_MERGED"
            ));
            ticketingPort.comment(new CommentWorkItemCommand(
                command.ticketSystem(), command.ticketKey(),
                buildMergedPRComment(command), "PR_MERGED"
            ));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to process merged PR %s for ticket %s",
                command.externalId(), command.ticketKey());
        }
    }

    private String buildMergedPRComment(Command command) {
        StringBuilder comment = new StringBuilder("Pull request merged and ready for validation.");
        if (command.prUrl() != null && !command.prUrl().isBlank()) {
            comment.append("\n\nPull request: ").append(command.prUrl());
        }
        if (command.prTitle() != null && !command.prTitle().isBlank()) {
            comment.append("\nTitle: ").append(command.prTitle());
        }
        if (command.prBody() != null && !command.prBody().isBlank()) {
            comment.append("\n\n").append(command.prBody());
        }
        return comment.toString();
    }

    public record Command(
        String ticketSystem,
        String ticketKey,
        String externalId,
        String prTitle,
        String prUrl,
        String prBody
    ) {}
}
