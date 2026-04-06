package io.devflow.infrastructure.codehost.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.application.command.agent.CancelAgentRunCommand;
import io.devflow.application.command.agent.StartAgentRunCommand;
import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.command.workspace.PrepareWorkspaceCommand;
import io.devflow.application.port.agent.AgentRuntimePort;
import io.devflow.application.port.codehost.CodeHostPort;
import io.devflow.application.port.ticketing.TicketingPort;
import io.devflow.application.runtime.DevFlowRuntime;
import io.devflow.application.service.WorkItemTransitionService;
import io.devflow.application.service.WorkspaceLayoutService;
import io.devflow.infrastructure.agent.opencode.OpenCodeRuntimeConfig;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.ticketing.ExternalCommentParentType;
import io.devflow.domain.ticketing.IncomingComment;
import io.devflow.domain.ticketing.WorkItem;
import io.devflow.domain.ticketing.WorkItemTransitionTarget;
import io.devflow.domain.workflow.WorkflowPhase;
import io.devflow.domain.workspace.PreparedWorkspace;
import io.devflow.domain.workspace.RepositoryWorkspace;
import io.devflow.infrastructure.ticketing.jira.JiraConfig;
import io.devflow.infrastructure.ticketing.jira.JiraSystem;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GitHubPollingJob {

    private static final Logger LOG = Logger.getLogger(GitHubPollingJob.class);

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP_TYPE = new TypeReference<>() {
    };
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String GITHUB_ACCEPT_HEADER = "application/vnd.github+json";
    private static final String AUTH_SCHEME_BEARER = "Bearer ";
    private static final String API_PULLS_PATH_TEMPLATE = "/repos/%s/pulls?state=open&per_page=100";
    private static final String API_REVIEW_COMMENTS_PATH_TEMPLATE = "/repos/%s/pulls/%s/comments?per_page=100&sort=updated&direction=desc";
    private static final String API_CLOSED_PULLS_PATH_TEMPLATE = "/repos/%s/pulls?state=closed&sort=updated&direction=desc&per_page=25";
    private static final String API_COMMITS_PATH_TEMPLATE = "/repos/%s/commits?sha=%s&per_page=1";
    private static final String PAYLOAD_NUMBER = "number";
    private static final String PAYLOAD_HTML_URL = "html_url";
    private static final String PAYLOAD_UPDATED_AT = "updated_at";
    private static final String PAYLOAD_CREATED_AT = "created_at";
    private static final String PAYLOAD_HEAD = "head";
    private static final String PAYLOAD_BASE = "base";
    private static final String PAYLOAD_REF = "ref";
    private static final String PAYLOAD_ID = "id";
    private static final String PAYLOAD_USER = "user";
    private static final String PAYLOAD_LOGIN = "login";
    private static final String PAYLOAD_BODY = "body";
    private static final String PAYLOAD_TITLE = "title";
    private static final String PAYLOAD_MERGED_AT = "merged_at";
    private static final String PAYLOAD_COMMIT = "commit";
    private static final String PAYLOAD_COMMITTER = "committer";
    private static final String PAYLOAD_DATE = "date";

    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile(
        "^devflow/([A-Za-z]+-\\d+)(?:/.*)?$"
    );

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT)
        .build();

    @Inject
    GitHubConfig config;

    @Inject
    DevFlowRuntime runtime;

    @Inject
    AgentRuntimePort agentRuntimePort;

    @Inject
    CodeHostPort codeHostPort;

    @Inject
    TicketingPort ticketingPort;

    @Inject
    WorkspaceLayoutService workspaceLayoutService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkItemTransitionService workItemTransitionService;

    @Inject
    OpenCodeRuntimeConfig agentRuntimeConfig;

    @Inject
    JiraConfig jiraConfig;

    @Scheduled(
        every = "${devflow.github.poll-interval-minutes:1}m",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    void pollOpenPullRequests() {
        if (isBlank(config.token())) {
            return;
        }

        List<String> repositories = codeHostPort.configuredRepositories();
        if (repositories.isEmpty()) {
            return;
        }

        for (String repository : repositories) {
            processMergedPullRequests(repository);
        }

        cancelStaleRunIfNeeded();
        if (runtime.isBusy()) {
            LOG.info("Skipping GitHub review comment polling because an agent run is active");
            return;
        }

        for (String repository : repositories) {
            if (runtime.isBusy()) {
                return;
            }
            pollRepositoryPullRequests(repository);
        }
    }

    private void cancelStaleRunIfNeeded() {
        long maxDurationMinutes = effectiveStaleRunDurationMinutes();
        Duration maxDuration = Duration.ofMinutes(maxDurationMinutes);
        if (!runtime.isStale(maxDuration)) {
            return;
        }
        DevFlowRuntime.RunContext stale = runtime.current();
        if (stale == null) {
            return;
        }
        LOG.warnf(
            "Run %s for ticket %s has been active for more than %d minutes — cancelling",
            stale.agentRunId(), stale.ticketKey(), maxDurationMinutes
        );
        try {
            agentRuntimePort.cancelRun(new CancelAgentRunCommand(stale.agentRunId()));
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Failed to cancel stale agent run %s — clearing locally", stale.agentRunId());
        }
        runtime.clearRunIfMatches(stale.agentRunId());
    }

    private void pollRepositoryPullRequests(String repository) {
        List<Map<String, Object>> pullRequests = fetchOpenPullRequests(repository);
        List<Map<String, Object>> devflowPRs = pullRequests.stream()
            .filter(this::isDevflowBranch)
            .toList();

        if (!devflowPRs.isEmpty()) {
            LOG.infof("Found %d open devflow PRs in repository %s", devflowPRs.size(), repository);
        }

        for (Map<String, Object> pullRequest : devflowPRs) {
            if (runtime.isBusy()) {
                return;
            }
            checkPullRequestForReviewComments(repository, pullRequest);
        }
    }

    private void checkPullRequestForReviewComments(String repository, Map<String, Object> pullRequest) {
        String headBranch = extractHeadBranch(pullRequest);
        String baseBranch = extractBaseBranch(pullRequest);
        String ticketKey = extractTicketKey(headBranch);
        if (ticketKey == null) {
            LOG.debugf("Cannot extract ticket key from branch %s, skipping", headBranch);
            return;
        }

        Number prNumber = (Number) pullRequest.get(PAYLOAD_NUMBER);
        String prUrl = string(pullRequest.get(PAYLOAD_HTML_URL));
        String externalId = repository + GitHubCodeHostAdapter.PULL_REQUEST_EXTERNAL_ID_SEPARATOR + prNumber.longValue();

        List<Map<String, Object>> reviewComments = fetchReviewComments(repository, String.valueOf(prNumber.longValue()));
        if (reviewComments.isEmpty()) {
            return;
        }

        Map<String, Object> latestComment = reviewComments.stream()
            .filter(this::isHumanComment)
            .max(Comparator.comparing(comment -> extractInstant(comment.get(PAYLOAD_UPDATED_AT))))
            .orElse(null);

        if (latestComment == null) {
            return;
        }

        Instant commentDate = extractInstant(latestComment.get(PAYLOAD_CREATED_AT));
        Instant lastCommitDate = fetchLastCommitDate(repository, headBranch);
        if (commentDate != null && lastCommitDate != null && !commentDate.isAfter(lastCommitDate)) {
            LOG.debugf(
                "Review comment on PR %s for ticket %s predates last commit, already handled",
                externalId, ticketKey
            );
            return;
        }

        LOG.infof(
            "Found new review comment on PR %s for ticket %s — starting agent run",
            externalId, ticketKey
        );

        CodeChangeRef codeChange = new CodeChangeRef(
            GitHubSystem.ID,
            externalId,
            repository,
            prUrl,
            headBranch,
            baseBranch
        );
        IncomingComment comment = toIncomingComment(externalId, latestComment);
        startAgentRunForReviewComment(ticketKey, codeChange, comment, repository);
    }

    private void processMergedPullRequests(String repository) {
        List<Map<String, Object>> closedPRs = fetchRecentlyClosedPullRequests(repository);
        for (Map<String, Object> pr : closedPRs) {
            if (!isDevflowBranch(pr) || !isMerged(pr)) {
                continue;
            }
            String ticketKey = extractTicketKey(extractHeadBranch(pr));
            if (ticketKey == null) {
                continue;
            }
            handleMergedPullRequest(ticketKey, pr, repository);
        }
    }

    private void handleMergedPullRequest(String ticketKey, Map<String, Object> pr, String repository) {
        WorkItem workItem = safeLoadWorkItem(ticketKey);
        if (workItem == null) {
            return;
        }
        if (!jiraConfig.reviewStatus().equalsIgnoreCase(workItem.status())) {
            return;
        }

        Number prNumber = (Number) pr.get(PAYLOAD_NUMBER);
        String externalId = repository + GitHubCodeHostAdapter.PULL_REQUEST_EXTERNAL_ID_SEPARATOR + prNumber.longValue();
        String prTitle = string(pr.get(PAYLOAD_TITLE));
        String prUrl = string(pr.get(PAYLOAD_HTML_URL));
        String prBody = string(pr.get(PAYLOAD_BODY));

        LOG.infof("Detected merged PR %s for ticket %s — transitioning to 'To Validate'", externalId, ticketKey);

        try {
            workItemTransitionService.transitionDirect(
                JiraSystem.ID, ticketKey, WorkItemTransitionTarget.TO_VALIDATE, "PR_MERGED"
            );
            String comment = buildMergedPRComment(prTitle, prUrl, prBody);
            ticketingPort.comment(new CommentWorkItemCommand(JiraSystem.ID, ticketKey, comment, "PR_MERGED"));
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Failed to process merged PR %s for ticket %s", externalId, ticketKey);
        }
    }

    private boolean isMerged(Map<String, Object> pullRequest) {
        return pullRequest.get(PAYLOAD_MERGED_AT) != null;
    }

    private String buildMergedPRComment(String prTitle, String prUrl, String prBody) {
        StringBuilder comment = new StringBuilder("Pull request merged and ready for validation.");
        if (prUrl != null && !prUrl.isBlank()) {
            comment.append("\n\nPull request: ").append(prUrl);
        }
        if (prTitle != null && !prTitle.isBlank()) {
            comment.append("\nTitle: ").append(prTitle);
        }
        if (prBody != null && !prBody.isBlank()) {
            comment.append("\n\n").append(prBody);
        }
        return comment.toString();
    }

    private void startAgentRunForReviewComment(
        String ticketKey,
        CodeChangeRef codeChange,
        IncomingComment reviewComment,
        String repository
    ) {
        UUID workflowId = UUID.randomUUID();
        UUID agentRunId = UUID.randomUUID();
        String objective = "Address review comment on PR " + codeChange.externalId() + " for ticket " + ticketKey;

        runtime.startRun(
            workflowId, agentRunId, JiraSystem.ID, ticketKey,
            WorkflowPhase.IMPLEMENTATION, objective
        );

        Map<String, Object> snapshot = buildSnapshot(workflowId, ticketKey, codeChange, reviewComment);
        workspaceLayoutService.ensureDirectories(workflowId);

        Map<String, Object> preparedSnapshot = prepareSnapshotWithWorkspace(
            workflowId, repository, codeChange, snapshot
        );
        preparedSnapshot.put("phase", WorkflowPhase.IMPLEMENTATION.name());

        StartAgentRunCommand command = StartAgentRunCommand.start(
            UUID.randomUUID(),
            workflowId,
            agentRunId,
            WorkflowPhase.IMPLEMENTATION,
            objective,
            preparedSnapshot
        );

        try {
            LOG.infof("Dispatching agent run %s for review comment on ticket %s", agentRunId, ticketKey);
            agentRuntimePort.startRun(command);
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Failed to dispatch agent run for review comment on ticket %s", ticketKey);
            runtime.clearRun();
        }
    }

    private Map<String, Object> buildSnapshot(
        UUID workflowId,
        String ticketKey,
        CodeChangeRef codeChange,
        IncomingComment reviewComment
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowId", workflowId.toString());
        snapshot.put("workItemSystem", JiraSystem.ID);
        snapshot.put("workItemKey", ticketKey);
        snapshot.put("codeChange", codeChange);
        snapshot.put("reviewComment", reviewComment);

        WorkItem workItem = safeLoadWorkItem(ticketKey);
        if (workItem != null) {
            snapshot.put("workItem", workItem);
        }

        return snapshot;
    }

    private Map<String, Object> prepareSnapshotWithWorkspace(
        UUID workflowId,
        String repository,
        CodeChangeRef codeChange,
        Map<String, Object> inputSnapshot
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>(inputSnapshot);
        Map<String, String> preferredBranches = new LinkedHashMap<>();
        if (codeChange.repository() != null && codeChange.sourceBranch() != null) {
            preferredBranches.put(codeChange.repository(), codeChange.sourceBranch());
        }

        PreparedWorkspace preparedWorkspace = codeHostPort.prepareWorkspace(
            new PrepareWorkspaceCommand(workflowId, List.of(repository), preferredBranches)
        );

        Map<String, Object> workspace = new LinkedHashMap<>(workspaceLayoutService.describe(workflowId));
        workspace.put("projectRoot", preparedWorkspace.projectRoot());
        workspace.put("repositories", preparedWorkspace.repositories().stream()
            .map(this::toWorkspaceEntry)
            .toList());
        snapshot.put("workspace", workspace);
        snapshot.put("repositories", preparedWorkspace.repositories().stream()
            .map(RepositoryWorkspace::repository)
            .toList());
        return snapshot;
    }

    private Map<String, Object> toWorkspaceEntry(RepositoryWorkspace workspace) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("repository", workspace.repository());
        entry.put("projectRoot", workspace.projectRoot());
        return entry;
    }

    private boolean isDevflowBranch(Map<String, Object> pullRequest) {
        String headBranch = extractHeadBranch(pullRequest);
        return headBranch != null && headBranch.startsWith(GitHubCodeHostAdapter.BRANCH_PREFIX);
    }

    private String extractHeadBranch(Map<String, Object> pullRequest) {
        return string(map(pullRequest.get(PAYLOAD_HEAD)).get(PAYLOAD_REF));
    }

    private String extractBaseBranch(Map<String, Object> pullRequest) {
        return string(map(pullRequest.get(PAYLOAD_BASE)).get(PAYLOAD_REF));
    }

    private String extractTicketKey(String branchName) {
        if (branchName == null) {
            return null;
        }
        Matcher matcher = TICKET_KEY_PATTERN.matcher(branchName);
        if (matcher.matches()) {
            return matcher.group(1).toUpperCase();
        }
        return null;
    }

    private boolean isHumanComment(Map<String, Object> comment) {
        Map<String, Object> user = map(comment.get(PAYLOAD_USER));
        String login = string(user.get(PAYLOAD_LOGIN));
        return login != null && !login.endsWith("[bot]");
    }

    private IncomingComment toIncomingComment(String externalId, Map<String, Object> comment) {
        Map<String, Object> user = map(comment.get(PAYLOAD_USER));
        return new IncomingComment(
            string(comment.get(PAYLOAD_ID)),
            ExternalCommentParentType.CODE_CHANGE.id(),
            externalId,
            string(user.get(PAYLOAD_LOGIN)),
            string(comment.get(PAYLOAD_BODY)),
            extractInstant(comment.get(PAYLOAD_CREATED_AT)),
            extractInstant(comment.get(PAYLOAD_UPDATED_AT))
        );
    }

    private WorkItem safeLoadWorkItem(String ticketKey) {
        try {
            return ticketingPort.loadWorkItem(JiraSystem.ID, ticketKey).orElse(null);
        } catch (RuntimeException exception) {
            LOG.debugf("Unable to load Jira work item %s for GitHub PR context (non-critical)", ticketKey);
            return null;
        }
    }

    private List<Map<String, Object>> fetchOpenPullRequests(String repository) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.apiUrl() + API_PULLS_PATH_TEMPLATE.formatted(repository)))
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BEARER + config.token())
            .header(HEADER_ACCEPT, GITHUB_ACCEPT_HEADER)
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOG.warnf("GitHub PR listing failed for %s: HTTP %d", repository, response.statusCode());
                return List.of();
            }
            return objectMapper.readValue(response.body(), LIST_OF_MAP_TYPE);
        } catch (IOException exception) {
            LOG.warnf(exception, "Unable to list GitHub pull requests for %s", repository);
            return List.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while listing GitHub pull requests", exception);
        }
    }

    private List<Map<String, Object>> fetchReviewComments(String repository, String pullRequestNumber) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.apiUrl()
                + API_REVIEW_COMMENTS_PATH_TEMPLATE.formatted(repository, pullRequestNumber)))
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BEARER + config.token())
            .header(HEADER_ACCEPT, GITHUB_ACCEPT_HEADER)
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOG.warnf(
                    "GitHub review comment listing failed for %s PR #%s: HTTP %d",
                    repository, pullRequestNumber, response.statusCode()
                );
                return List.of();
            }
            return objectMapper.readValue(response.body(), LIST_OF_MAP_TYPE);
        } catch (IOException exception) {
            LOG.warnf(exception, "Unable to list GitHub review comments for %s PR #%s", repository, pullRequestNumber);
            return List.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while listing GitHub review comments", exception);
        }
    }

    private List<Map<String, Object>> fetchRecentlyClosedPullRequests(String repository) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.apiUrl() + API_CLOSED_PULLS_PATH_TEMPLATE.formatted(repository)))
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BEARER + config.token())
            .header(HEADER_ACCEPT, GITHUB_ACCEPT_HEADER)
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOG.warnf("GitHub closed PR listing failed for %s: HTTP %d", repository, response.statusCode());
                return List.of();
            }
            return objectMapper.readValue(response.body(), LIST_OF_MAP_TYPE);
        } catch (IOException exception) {
            LOG.warnf(exception, "Unable to list closed GitHub pull requests for %s", repository);
            return List.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while listing closed GitHub pull requests", exception);
        }
    }

    private Instant fetchLastCommitDate(String repository, String branch) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.apiUrl() + API_COMMITS_PATH_TEMPLATE.formatted(repository, branch)))
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BEARER + config.token())
            .header(HEADER_ACCEPT, GITHUB_ACCEPT_HEADER)
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOG.debugf("GitHub commit listing failed for %s/%s: HTTP %d", repository, branch, response.statusCode());
                return null;
            }
            List<Map<String, Object>> commits = objectMapper.readValue(response.body(), LIST_OF_MAP_TYPE);
            if (commits.isEmpty()) {
                return null;
            }
            Map<String, Object> commit = map(commits.getFirst().get(PAYLOAD_COMMIT));
            Map<String, Object> committer = map(commit.get(PAYLOAD_COMMITTER));
            return extractInstant(committer.get(PAYLOAD_DATE));
        } catch (IOException exception) {
            LOG.debugf(exception, "Unable to fetch last commit date for %s/%s", repository, branch);
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching last commit date", exception);
        }
    }

    private Instant extractInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (DateTimeParseException exception) {
            LOG.debugf("Unable to parse instant from value: %s", value);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> current ? (Map<String, Object>) current : Map.of();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long effectiveStaleRunDurationMinutes() {
        return (long) agentRuntimeConfig.hardTimeoutMinutes() + agentRuntimeConfig.staleTimeoutBufferMinutes();
    }
}
