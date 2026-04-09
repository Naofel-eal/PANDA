package io.devflow.infrastructure.ticketing.jira;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.domain.model.ticketing.IncomingComment;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JiraTicketPollingJobTest {

    @Test
    @DisplayName("Given a user comment newer than DevFlow feedback when comments are evaluated then the ticket should resume")
    void givenUserCommentNewerThanDevFlowFeedback_whenCommentsAreEvaluated_thenTheTicketShouldResume() {
        List<IncomingComment> comments = List.of(
            comment("devflow", "Pull request merged and ready for validation.", "2026-04-05T10:00:00Z"),
            comment("user-1", "Validation found an issue to fix.", "2026-04-05T10:05:00Z")
        );

        assertTrue(JiraTicketPollingJob.hasNewUserCommentSinceLastDevflowComment(
            comments,
            comment -> "devflow".equals(comment.authorId())
        ));
    }

    @Test
    @DisplayName("Given the latest comment still comes from DevFlow when comments are evaluated then the ticket should stay idle")
    void givenTheLatestCommentStillComesFromDevFlow_whenCommentsAreEvaluated_thenTheTicketShouldStayIdle() {
        List<IncomingComment> comments = List.of(
            comment("user-1", "Can you adjust this flow?", "2026-04-05T10:00:00Z"),
            comment("devflow", "Implementation completed and ready for technical validation.", "2026-04-05T10:05:00Z")
        );

        assertFalse(JiraTicketPollingJob.hasNewUserCommentSinceLastDevflowComment(
            comments,
            comment -> "devflow".equals(comment.authorId())
        ));
    }

    @Test
    @DisplayName("Given a user comment and no previous DevFlow feedback when comments are evaluated then the ticket should resume")
    void givenUserCommentAndNoPreviousDevFlowFeedback_whenCommentsAreEvaluated_thenTheTicketShouldResume() {
        List<IncomingComment> comments = List.of(
            comment("user-1", "Please start from this feedback.", "2026-04-05T10:00:00Z")
        );

        assertTrue(JiraTicketPollingJob.hasNewUserCommentSinceLastDevflowComment(
            comments,
            comment -> "devflow".equals(comment.authorId())
        ));
    }

    private IncomingComment comment(String authorId, String body, String createdAt) {
        return new IncomingComment(
            "comment-" + createdAt,
            "WORK_ITEM",
            "DEV-1",
            authorId,
            body,
            Instant.parse(createdAt),
            null
        );
    }
}
