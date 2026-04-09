package io.devflow.infrastructure.codehost.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.application.codehost.merge.HandleMergedPullRequestUseCase;
import io.devflow.application.codehost.port.CodeHostPort;
import io.devflow.application.codehost.review.HandleReviewCommentUseCase;
import io.devflow.application.command.codehost.PublishCodeChangesCommand;
import io.devflow.application.command.workspace.PrepareWorkspaceCommand;
import io.devflow.application.ticketing.port.TicketingPort;
import io.devflow.application.workflow.cancel.CancelStaleRunUseCase;
import io.devflow.application.workflow.port.WorkflowHolder;
import io.devflow.domain.model.codehost.CodeChangeRef;
import io.devflow.domain.model.ticketing.IncomingComment;
import io.devflow.domain.model.ticketing.WorkItem;
import io.devflow.domain.model.workflow.Workflow;
import io.devflow.domain.model.workflow.WorkflowPhase;
import io.devflow.domain.model.workspace.PreparedWorkspace;
import io.devflow.infrastructure.agent.opencode.OpenCodeRuntimeConfig;
import io.devflow.infrastructure.ticketing.jira.JiraConfig;
import io.devflow.support.ReflectionTestSupport;
import io.devflow.support.StubHttpServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GitHubPollingJobBehaviorTest {

    @Test
    @DisplayName("Given merged and reviewed DevFlow pull requests when GitHub is polled then DevFlow routes merges to validation and fresh human feedback back to implementation")
    void givenMergedAndReviewedDevFlowPullRequests_whenGitHubIsPolled_thenDevFlowRoutesMergesToValidationAndFreshHumanFeedbackBackToImplementation() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/repos/acme/api/pulls?state=closed&sort=updated&direction=desc&per_page=25", 200, """
                [{
                  "number":11,
                  "title":"Validate release",
                  "html_url":"https://github.com/acme/api/pull/11",
                  "body":"Merged body",
                  "merged_at":"2026-04-09T10:10:00Z",
                  "head":{"ref":"devflow/SCRUM-60/release"},
                  "base":{"ref":"main"}
                }]
                """);
            server.enqueueExact("GET", "/repos/acme/api/pulls?state=open&per_page=100", 200, """
                [{
                  "number":12,
                  "html_url":"https://github.com/acme/api/pull/12",
                  "head":{"ref":"devflow/SCRUM-61/review-fixes"},
                  "base":{"ref":"main"}
                }]
                """);
            server.enqueueExact("GET", "/repos/acme/api/pulls/12/comments?per_page=100&sort=updated&direction=desc", 200, """
                [
                  {"id":"c2","body":"Ignore bot","created_at":"2026-04-09T10:06:00Z","updated_at":"2026-04-09T10:06:00Z","user":{"login":"ci-bot[bot]"}},
                  {"id":"c1","body":"Please rename the method","created_at":"2026-04-09T10:05:00Z","updated_at":"2026-04-09T10:05:00Z","user":{"login":"alice"}}
                ]
                """);
            server.enqueueExact("GET", "/repos/acme/api/issues/12/comments?per_page=100&sort=updated&direction=desc", 200, """
                [
                  {"id":"c3","body":"Add a regression test","created_at":"2026-04-09T10:07:00Z","updated_at":"2026-04-09T10:07:00Z","user":{"login":"bob"}}
                ]
                """);
            server.enqueueExact("GET", "/repos/acme/api/commits?sha=devflow/SCRUM-61/review-fixes&per_page=1", 200, """
                [{"commit":{"committer":{"date":"2026-04-09T10:00:00Z"}}}]
                """);

            RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder(false);
            RecordingTicketingPort ticketingPort = new RecordingTicketingPort(Map.of(
                "SCRUM-60", workItem("SCRUM-60", "To Review", Instant.parse("2026-04-09T10:05:00Z")),
                "SCRUM-61", workItem("SCRUM-61", "To Review", Instant.parse("2026-04-09T09:50:00Z"))
            ));
            RecordingMergedPullRequestUseCase mergedUseCase = new RecordingMergedPullRequestUseCase();
            RecordingReviewCommentUseCase reviewUseCase = new RecordingReviewCommentUseCase();
            RecordingCancelStaleRunUseCase cancelUseCase = new RecordingCancelStaleRunUseCase();
            GitHubPollingJob job = job(server.baseUrl(), workflowHolder, ticketingPort, mergedUseCase, reviewUseCase, cancelUseCase);

            job.pollOpenPullRequests();

            assertEquals(20, cancelUseCase.maxDurationMinutes);
            assertNotNull(mergedUseCase.command);
            assertEquals("SCRUM-60", mergedUseCase.command.ticketKey());
            assertEquals("acme/api#11", mergedUseCase.command.externalId());
            assertNotNull(reviewUseCase.command);
            assertEquals("SCRUM-61", reviewUseCase.command.ticketKey());
            assertEquals("acme/api#12", reviewUseCase.command.codeChange().externalId());
            assertEquals(2, reviewUseCase.command.reviewComments().size());
            assertEquals("Please rename the method", reviewUseCase.command.reviewComments().get(0).body());
            assertEquals("Add a regression test", reviewUseCase.command.reviewComments().get(1).body());
        }
    }

    @Test
    @DisplayName("Given an active workflow when GitHub is polled then DevFlow still checks stale runs but skips review comment polling")
    void givenAnActiveWorkflow_whenGitHubIsPolled_thenDevFlowStillChecksStaleRunsButSkipsReviewCommentPolling() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/repos/acme/api/pulls?state=closed&sort=updated&direction=desc&per_page=25", 200, "[]");

            RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder(true);
            RecordingCancelStaleRunUseCase cancelUseCase = new RecordingCancelStaleRunUseCase();
            RecordingReviewCommentUseCase reviewUseCase = new RecordingReviewCommentUseCase();
            GitHubPollingJob job = job(
                server.baseUrl(),
                workflowHolder,
                new RecordingTicketingPort(Map.of()),
                new RecordingMergedPullRequestUseCase(),
                reviewUseCase,
                cancelUseCase
            );

            job.pollOpenPullRequests();

            assertEquals(20, cancelUseCase.maxDurationMinutes);
            assertNull(reviewUseCase.command);
        }
    }

    @Test
    @DisplayName("Given GitHub polling is not configured when the job runs then DevFlow exits immediately without touching workflows")
    void givenGitHubPollingIsNotConfigured_whenTheJobRuns_thenDevFlowExitsImmediatelyWithoutTouchingWorkflows() {
        GitHubPollingJob job = new GitHubPollingJob();
        ReflectionTestSupport.setField(job, "config", new GitHubConfig() {
            @Override public String apiUrl() { return "http://github"; }
            @Override public String token() { return " "; }
            @Override public String defaultBaseBranch() { return "main"; }
            @Override public String commitUserName() { return "DevFlow"; }
            @Override public String commitUserEmail() { return "devflow@example.com"; }
            @Override public int pollIntervalMinutes() { return 1; }
            @Override public List<Repository> repositories() { return List.of(); }
        });
        RecordingCancelStaleRunUseCase cancelUseCase = new RecordingCancelStaleRunUseCase();
        ReflectionTestSupport.setField(job, "cancelStaleRunUseCase", cancelUseCase);
        ReflectionTestSupport.setField(job, "codeHostPort", new StubCodeHostPort());

        job.pollOpenPullRequests();

        assertEquals(0, cancelUseCase.maxDurationMinutes);
    }

    @Test
    @DisplayName("Given GitHub is temporarily unavailable when polling runs then DevFlow skips the repository safely and still checks stale workflows")
    void givenGitHubIsTemporarilyUnavailable_whenPollingRuns_thenDevFlowSkipsTheRepositorySafelyAndStillChecksStaleWorkflows() {
        RecordingCancelStaleRunUseCase cancelUseCase = new RecordingCancelStaleRunUseCase();
        RecordingMergedPullRequestUseCase mergedUseCase = new RecordingMergedPullRequestUseCase();
        RecordingReviewCommentUseCase reviewUseCase = new RecordingReviewCommentUseCase();
        GitHubPollingJob job = job(
            "http://127.0.0.1:9",
            new RecordingWorkflowHolder(false),
            new RecordingTicketingPort(Map.of()),
            mergedUseCase,
            reviewUseCase,
            cancelUseCase
        );

        job.pollOpenPullRequests();

        assertEquals(20, cancelUseCase.maxDurationMinutes);
        assertNull(mergedUseCase.command);
        assertNull(reviewUseCase.command);
    }

    @Test
    @DisplayName("Given every review comment is older than the latest commit when GitHub is polled then DevFlow keeps the ticket in review")
    void givenEveryReviewCommentIsOlderThanTheLatestCommit_whenGitHubIsPolled_thenDevFlowKeepsTheTicketInReview() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/repos/acme/api/pulls?state=closed&sort=updated&direction=desc&per_page=25", 200, "[]");
            server.enqueueExact("GET", "/repos/acme/api/pulls?state=open&per_page=100", 200, """
                [{
                  "number":14,
                  "html_url":"https://github.com/acme/api/pull/14",
                  "head":{"ref":"devflow/SCRUM-62/review-fixes"},
                  "base":{"ref":"main"}
                }]
                """);
            server.enqueueExact("GET", "/repos/acme/api/pulls/14/comments?per_page=100&sort=updated&direction=desc", 200, """
                [
                  {"id":"c1","body":"Old feedback","created_at":"2026-04-09T10:01:00Z","updated_at":"2026-04-09T10:01:00Z","user":{"login":"alice"}}
                ]
                """);
            server.enqueueExact("GET", "/repos/acme/api/issues/14/comments?per_page=100&sort=updated&direction=desc", 200, "[]");
            server.enqueueExact("GET", "/repos/acme/api/commits?sha=devflow/SCRUM-62/review-fixes&per_page=1", 200, """
                [{"commit":{"committer":{"date":"2026-04-09T10:05:00Z"}}}]
                """);

            RecordingReviewCommentUseCase reviewUseCase = new RecordingReviewCommentUseCase();
            GitHubPollingJob job = job(
                server.baseUrl(),
                new RecordingWorkflowHolder(false),
                new RecordingTicketingPort(Map.of(
                    "SCRUM-62", workItem("SCRUM-62", "To Review", Instant.parse("2026-04-09T09:50:00Z"))
                )),
                new RecordingMergedPullRequestUseCase(),
                reviewUseCase,
                new RecordingCancelStaleRunUseCase()
            );

            job.pollOpenPullRequests();

            assertNull(reviewUseCase.command);
        }
    }

    private GitHubPollingJob job(
        String apiUrl,
        RecordingWorkflowHolder workflowHolder,
        RecordingTicketingPort ticketingPort,
        RecordingMergedPullRequestUseCase mergedUseCase,
        RecordingReviewCommentUseCase reviewUseCase,
        RecordingCancelStaleRunUseCase cancelUseCase
    ) {
        GitHubPollingJob job = new GitHubPollingJob();
        ReflectionTestSupport.setField(job, "config", new GitHubConfig() {
            @Override public String apiUrl() { return apiUrl; }
            @Override public String token() { return "github-token"; }
            @Override public String defaultBaseBranch() { return "main"; }
            @Override public String commitUserName() { return "DevFlow"; }
            @Override public String commitUserEmail() { return "devflow@example.com"; }
            @Override public int pollIntervalMinutes() { return 1; }
            @Override public List<Repository> repositories() {
                return List.of(new Repository() {
                    @Override public String name() { return "acme/api"; }
                    @Override public String baseBranch() { return "main"; }
                });
            }
        });
        ReflectionTestSupport.setField(job, "workflowHolder", workflowHolder);
        ReflectionTestSupport.setField(job, "codeHostPort", new StubCodeHostPort());
        ReflectionTestSupport.setField(job, "ticketingPort", ticketingPort);
        ReflectionTestSupport.setField(job, "objectMapper", new ObjectMapper());
        ReflectionTestSupport.setField(job, "agentRuntimeConfig", new OpenCodeRuntimeConfig() {
            @Override public String baseUrl() { return "http://agent"; }
            @Override public int hardTimeoutMinutes() { return 15; }
            @Override public int staleTimeoutBufferMinutes() { return 5; }
        });
        ReflectionTestSupport.setField(job, "jiraConfig", new JiraConfig() {
            @Override public String baseUrl() { return "http://jira"; }
            @Override public String userEmail() { return "devflow@example.com"; }
            @Override public String apiToken() { return "jira-token"; }
            @Override public String epicKey() { return "SCRUM"; }
            @Override public String todoStatus() { return "To Do"; }
            @Override public String inProgressStatus() { return "In Progress"; }
            @Override public String blockedStatus() { return "Blocked"; }
            @Override public String reviewStatus() { return "To Review"; }
            @Override public String validateStatus() { return "To Validate"; }
            @Override public String doneStatus() { return "Done"; }
            @Override public int pollIntervalMinutes() { return 1; }
            @Override public int pollMaxResults() { return 100; }
        });
        ReflectionTestSupport.setField(job, "handleMergedPullRequestUseCase", mergedUseCase);
        ReflectionTestSupport.setField(job, "handleReviewCommentUseCase", reviewUseCase);
        ReflectionTestSupport.setField(job, "cancelStaleRunUseCase", cancelUseCase);
        return job;
    }

    private static WorkItem workItem(String key, String status, Instant updatedAt) {
        return new WorkItem(key, "Story", key, "Description", status, "https://jira.example/browse/" + key, List.of(), List.of(), updatedAt);
    }

    private static final class StubCodeHostPort implements CodeHostPort {
        @Override
        public List<CodeChangeRef> publish(PublishCodeChangesCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> configuredRepositories() {
            return List.of("acme/api");
        }

        @Override
        public PreparedWorkspace prepareWorkspace(PrepareWorkspaceCommand command) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingWorkflowHolder implements WorkflowHolder {
        private final boolean busy;

        private RecordingWorkflowHolder(boolean busy) {
            this.busy = busy;
        }

        @Override public boolean isBusy() { return busy; }
        @Override public boolean isStale(Duration maxDuration) { return false; }
        @Override public Workflow current() { return null; }
        @Override public Workflow start(Workflow workflow) { throw new UnsupportedOperationException(); }
        @Override public void clear() { }
        @Override public void clearIfMatches(UUID agentRunId) { }
        @Override public Workflow replacePhase(WorkflowPhase newPhase, UUID newAgentRunId) { throw new UnsupportedOperationException(); }
        @Override public void addPublishedPR(CodeChangeRef codeChange) { }
    }

    private static final class RecordingTicketingPort implements TicketingPort {
        private final Map<String, WorkItem> workItems;
        private boolean failLoad;

        private RecordingTicketingPort(Map<String, WorkItem> workItems) {
            this.workItems = workItems;
        }

        @Override public void comment(io.devflow.application.command.ticketing.CommentWorkItemCommand command) { }
        @Override public void transition(io.devflow.application.command.ticketing.TransitionWorkItemCommand command) { }
        @Override public Optional<WorkItem> loadWorkItem(String workItemSystem, String workItemKey) {
            if (failLoad) {
                throw new IllegalStateException("jira unavailable");
            }
            return Optional.ofNullable(workItems.get(workItemKey));
        }
        @Override public List<IncomingComment> listComments(String workItemSystem, String workItemKey) { return List.of(); }
    }

    private static final class RecordingMergedPullRequestUseCase extends HandleMergedPullRequestUseCase {
        private Command command;

        @Override
        public void execute(Command command) {
            this.command = command;
        }
    }

    private static final class RecordingReviewCommentUseCase extends HandleReviewCommentUseCase {
        private Command command;

        @Override
        public void execute(Command command) {
            this.command = command;
        }
    }

    private static final class RecordingCancelStaleRunUseCase extends CancelStaleRunUseCase {
        private long maxDurationMinutes;

        @Override
        public void execute(long maxDurationMinutes) {
            this.maxDurationMinutes = maxDurationMinutes;
        }
    }
}
