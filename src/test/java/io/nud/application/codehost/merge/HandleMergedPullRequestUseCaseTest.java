package io.nud.application.codehost.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nud.application.command.ticketing.CommentWorkItemCommand;
import io.nud.application.command.ticketing.TransitionWorkItemCommand;
import io.nud.application.ticketing.port.TicketingPort;
import io.nud.domain.model.ticketing.WorkItemTransitionTarget;
import io.nud.support.ReflectionTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HandleMergedPullRequestUseCaseTest {

    @Test
    @DisplayName("Given a merged pull request when validation starts then the ticket moves to business validation with the merge summary")
    void givenMergedPullRequest_whenValidationStarts_thenTicketMovesToBusinessValidationWithMergeSummary() {
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        HandleMergedPullRequestUseCase useCase = new HandleMergedPullRequestUseCase();
        ReflectionTestSupport.setField(useCase, "ticketingPort", ticketingPort);

        useCase.execute(new HandleMergedPullRequestUseCase.Command(
            "jira",
            "SCRUM-1",
            "repo#1",
            "feat: helper text",
            "https://github.example/pr/1",
            "Business summary"
        ));

        assertEquals(WorkItemTransitionTarget.TO_VALIDATE, ticketingPort.transition.target());
        assertEquals("PR_MERGED", ticketingPort.transition.reasonCode());
        assertEquals("PR_MERGED", ticketingPort.comment.reasonCode());
        assertEquals(true, ticketingPort.comment.comment().contains("Pull request merged and ready for validation."));
        assertEquals(true, ticketingPort.comment.comment().contains("feat: helper text"));
    }

    @Test
    @DisplayName("Given a ticketing failure when a merged pull request is processed then NUD swallows the error to keep polling alive")
    void givenTicketingFailure_whenMergedPullRequestIsProcessed_thenNUDSwallowsTheErrorToKeepPollingAlive() {
        HandleMergedPullRequestUseCase useCase = new HandleMergedPullRequestUseCase();
        ReflectionTestSupport.setField(useCase, "ticketingPort", new TicketingPort() {
            @Override
            public void comment(CommentWorkItemCommand command) {
                throw new IllegalStateException("jira unavailable");
            }

            @Override
            public void transition(TransitionWorkItemCommand command) {
                throw new IllegalStateException("jira unavailable");
            }
        });

        useCase.execute(new HandleMergedPullRequestUseCase.Command(
            "jira",
            "SCRUM-2",
            "repo#2",
            "",
            "",
            ""
        ));
    }

    private static final class RecordingTicketingPort implements TicketingPort {
        private CommentWorkItemCommand comment;
        private TransitionWorkItemCommand transition;

        @Override
        public void comment(CommentWorkItemCommand command) {
            this.comment = command;
        }

        @Override
        public void transition(TransitionWorkItemCommand command) {
            this.transition = command;
        }
    }
}
