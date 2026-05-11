package io.panda.infrastructure.codehost.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.panda.application.codehost.merge.HandleMergedPullRequestUseCase;
import io.panda.application.codehost.port.CodeHostPort;
import io.panda.application.codehost.review.HandleReviewCommentUseCase;
import io.panda.application.command.codehost.PublishCodeChangesCommand;
import io.panda.application.command.workspace.PrepareWorkspaceCommand;
import io.panda.application.ticketing.port.TicketingPort;
import io.panda.application.workflow.cancel.CancelStaleRunUseCase;
import io.panda.application.workflow.port.WorkflowHolder;
import io.panda.domain.model.codehost.CodeChangeRef;
import io.panda.domain.model.ticketing.IncomingComment;
import io.panda.domain.model.ticketing.WorkItem;
import io.panda.domain.model.workflow.Workflow;
import io.panda.domain.model.workflow.WorkflowPhase;
import io.panda.domain.model.workspace.PreparedWorkspace;
import io.panda.infrastructure.agent.opencode.OpenCodeRuntimeConfig;
import io.panda.infrastructure.ticketing.jira.JiraConfig;
import io.panda.support.ReflectionTestSupport;
import io.panda.support.StubHttpServer;
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
    @DisplayName("Given merged and reviewed PANDA pull requests when GitHub is polled then PANDA routes merges to validation and fresh human feedback back to implementation")
    void givenMergedAndReviewedPANDAPullRequests_whenGitHubIsPolled_thenPANDARoutesMergesToValidationAndFreshHumanFeedbackBackToImplementation() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/repos/acme/api/pulls?state=closed&sort=updated&direction=desc&per_page=25", 200, """
                [{
                  "number":11,
                  "title":"Validate release",
                  "html_url":"https://github.com/acme/api/pull/11",
                  "body":"Merged body",
                  "merged_at":"2026-04-09T10:10:00Z",
                  "head":{"ref":"panda/SCRUM-60/release"},
                  "base":{"ref":"main"}
                }]
                """);
            server.enqueueExact("GET", "/repos/acme/api/pulls?state=open&per_page=100", 200, """
                [{
                  "number":12,
                  "html_url":"https://github.com/acme/api/pull/12",
                  "head":{"ref":"panda/SCRUM-61/review-fixes"},
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
            server.enqueueExact("GET", "/repos/acme/api/commits?sha=panda/SCRUM-61/review-fixes&per_page=1", 200, """
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
    @DisplayName("Given an active workflow when GitHub is polled then PANDA still checks stale runs but skips review comment polling")
    void givenAnActiveWorkflow_whenGitHubIsPolled_thenPANDAStillChecksStaleRunsButSkipsReviewCommentPolling() {
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
    @DisplayName("Given GitHub polling is not configured when the job runs then PANDA exits immediately without touching workflows")
    void givenGitHubPollingIsNotConfigured_whenTheJobRuns_thenPANDAExitsImmediatelyWithoutTouchingWorkflows() {
        GitHubPollingJob job = new GitHubPollingJob();
        ReflectionTestSupport.setField(job, "config", new GitHubConfig() {
            @Override public String apiUrl() { return "http://github"; }
            @Override public Optional<String> token() { return Optional.of(" "); }
            @Override public String defaultBaseBranch() { return "main"; }
            @Override public String commitUserName() { return "PANDA"; }
            @Override public String commitUserEmail() { return "panda@example.com"; }
            @Override public int pollIntervalMinutes() { return 1; }
            @Override public Optional<String> appId() { return Optional.empty(); }
            @Override public Optional<String> appPrivateKey() { return Optional.empty(); }
            @Override public Optional<String> appInstallationId() { return Optional.empty(); }
            @Override public List<Repository> repositories() { return List.of(); }
        });
        ReflectionTestSupport.setField(job, "tokenProvider", new GitHubPatTokenProvider(" "));
        RecordingCancelStaleRunUseCase cancelUseCase = new RecordingCancelStaleRunUseCase();
        ReflectionTestSupport.setField(job, "cancelStaleRunUseCase", cancelUseCase);
        ReflectionTestSupport.setField(job, "codeHostPort", new StubCodeHostPort());

        job.pollOpenPullRequests();

        assertEquals(0, cancelUseCase.maxDurationMinutes);
    }

    @Test
    @DisplayName("Given GitHub is temporarily unavailable when polling runs then PANDA skips the repository safely and still checks stale workflows")
    void givenGitHubIsTemporarilyUnavailable_whenPollingRuns_thenPANDASkipsTheRepositorySafelyAndStillChecksStaleWorkflows() {
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
    @DisplayName("Given every review comment is older than the latest commit when GitHub is polled then PANDA keeps the ticket in review")
    void givenEveryReviewCommentIsOlderThanTheLatestCommit_whenGitHubIsPolled_thenPANDAKeepsTheTicketInReview() {
        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueueExact("GET", "/repos/acme/api/pulls?state=closed&sort=updated&direction=desc&per_page=25", 200, "[]");
            server.enqueueExact("GET", "/repos/acme/api/pulls?state=open&per_page=100", 200, """
                [{
                  "number":14,
                  "html_url":"https://github.com/acme/api/pull/14",
                  "head":{"ref":"panda/SCRUM-62/review-fixes"},
                  "base":{"ref":"main"}
                }]
                """);
            server.enqueueExact("GET", "/repos/acme/api/pulls/14/comments?per_page=100&sort=updated&direction=desc", 200, """
                [
                  {"id":"c1","body":"Old feedback","created_at":"2026-04-09T10:01:00Z","updated_at":"2026-04-09T10:01:00Z","user":{"login":"alice"}}
                ]
                """);
            server.enqueueExact("GET", "/repos/acme/api/issues/14/comments?per_page=100&sort=updated&direction=desc", 200, "[]");
            server.enqueueExact("GET", "/repos/acme/api/commits?sha=panda/SCRUM-62/review-fixes&per_page=1", 200, """
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
            @Override public Optional<String> token() { return Optional.of("github-token"); }
            @Override public String defaultBaseBranch() { return "main"; }
            @Override public String commitUserName() { return "PANDA"; }
            @Override public String commitUserEmail() { return "panda@example.com"; }
            @Override public int pollIntervalMinutes() { return 1; }
            @Override public Optional<String> appId() { return Optional.empty(); }
            @Override public Optional<String> appPrivateKey() { return Optional.empty(); }
            @Override public Optional<String> appInstallationId() { return Optional.empty(); }
            @Override public List<Repository> repositories() {
                return List.of(new Repository() {
                    @Override public String name() { return "acme/api"; }
                    @Override public String baseBranch() { return "main"; }
                });
            }
        });
        ReflectionTestSupport.setField(job, "tokenProvider", new GitHubPatTokenProvider("github-token"));
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
            @Override public String apiToken() { return "jira-token"; }
            @Override public String projectKey() { return "SCRUM"; }
            @Override public Optional<String> serviceAccountId() { return Optional.empty(); }
            @Override public String backlogStatus() { return "Backlog"; }
            @Override public String todoStatus() { return "To Do"; }
            @Override public String inProgressStatus() { return "In Progress"; }
            @Override public String blockedStatus() { return "Blocked"; }
            @Override public String reviewStatus() { return "To Review"; }
            @Override public String validateStatus() { return "To Validate"; }
            @Override public String doneStatus() { return "Done"; }
            @Override public int pollIntervalMinutes() { return 1; }
            @Override public int pollMaxResults() { return 100; }
            @Override public String sprintField() { return "customfield_10020"; }
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

        @Override public void comment(io.panda.application.command.ticketing.CommentWorkItemCommand command) { }
        @Override public void transition(io.panda.application.command.ticketing.TransitionWorkItemCommand command) { }
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
