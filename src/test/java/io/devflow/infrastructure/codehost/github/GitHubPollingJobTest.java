package io.devflow.infrastructure.codehost.github;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.domain.model.ticketing.WorkItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitHubPollingJobTest {

    @Test
    void ignoresMergedPullRequestOlderThanTicketUpdate() {
        WorkItem workItem = workItem("2026-04-09T14:02:45Z");

        assertFalse(GitHubPollingJob.wasMergedAfterTicketUpdate(
            workItem,
            Instant.parse("2026-04-09T13:58:10Z")
        ));
    }

    @Test
    void acceptsMergedPullRequestNewerThanTicketUpdate() {
        WorkItem workItem = workItem("2026-04-09T14:02:45Z");

        assertTrue(GitHubPollingJob.wasMergedAfterTicketUpdate(
            workItem,
            Instant.parse("2026-04-09T14:03:20Z")
        ));
    }

    @Test
    void acceptsMergedPullRequestWhenTicketUpdateIsUnknown() {
        WorkItem workItem = new WorkItem(
            "SCRUM-20",
            "Task",
            "Test",
            "Description",
            "To Review",
            "https://jira.example/browse/SCRUM-20",
            List.of(),
            List.of(),
            null
        );

        assertTrue(GitHubPollingJob.wasMergedAfterTicketUpdate(
            workItem,
            Instant.parse("2026-04-09T14:03:20Z")
        ));
    }

    private WorkItem workItem(String updatedAt) {
        return new WorkItem(
            "SCRUM-20",
            "Task",
            "Test",
            "Description",
            "To Review",
            "https://jira.example/browse/SCRUM-20",
            List.of(),
            List.of(),
            Instant.parse(updatedAt)
        );
    }
}
