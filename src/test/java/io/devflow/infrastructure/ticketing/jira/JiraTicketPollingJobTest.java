package io.devflow.infrastructure.ticketing.jira;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.domain.model.ticketing.IncomingComment;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JiraTicketPollingJobTest {

    @Test
    void resumesWhenUserCommentIsNewerThanDevflowComment() {
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
    void doesNotResumeWhenLatestCommentStillComesFromDevflow() {
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
    void resumesWhenThereIsUserCommentAndNoPreviousDevflowComment() {
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
