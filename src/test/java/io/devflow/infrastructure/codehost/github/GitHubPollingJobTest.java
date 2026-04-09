package io.devflow.infrastructure.codehost.github;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.domain.model.ticketing.WorkItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GitHubPollingJobTest {

    @Test
    @DisplayName("Given a merged pull request older than the Jira update when it is evaluated then DevFlow ignores it")
    void givenMergedPullRequestOlderThanJiraUpdate_whenItIsEvaluated_thenDevFlowIgnoresIt() {
        WorkItem workItem = workItem("2026-04-09T14:02:45Z");

        assertFalse(GitHubPollingJob.wasMergedAfterTicketUpdate(
            workItem,
            Instant.parse("2026-04-09T13:58:10Z")
        ));
    }

    @Test
    @DisplayName("Given a merged pull request newer than the Jira update when it is evaluated then DevFlow accepts it")
    void givenMergedPullRequestNewerThanJiraUpdate_whenItIsEvaluated_thenDevFlowAcceptsIt() {
        WorkItem workItem = workItem("2026-04-09T14:02:45Z");

        assertTrue(GitHubPollingJob.wasMergedAfterTicketUpdate(
            workItem,
            Instant.parse("2026-04-09T14:03:20Z")
        ));
    }

    @Test
    @DisplayName("Given a merged pull request with no Jira update timestamp when it is evaluated then DevFlow accepts it")
    void givenMergedPullRequestWithNoJiraUpdateTimestamp_whenItIsEvaluated_thenDevFlowAcceptsIt() {
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

    @Test
    @DisplayName("Given the ticket or merge timestamp is missing when a merged pull request is evaluated then DevFlow ignores it safely")
    void givenTheTicketOrMergeTimestampIsMissing_whenAMergedPullRequestIsEvaluated_thenDevFlowIgnoresItSafely() {
        assertFalse(GitHubPollingJob.wasMergedAfterTicketUpdate(null, Instant.parse("2026-04-09T14:03:20Z")));
        assertFalse(GitHubPollingJob.wasMergedAfterTicketUpdate(workItem("2026-04-09T14:02:45Z"), null));
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
