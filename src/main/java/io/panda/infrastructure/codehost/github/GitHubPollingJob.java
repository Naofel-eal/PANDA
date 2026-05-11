package io.panda.infrastructure.codehost.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.panda.application.codehost.merge.HandleMergedPullRequestUseCase;
import io.panda.application.codehost.port.CodeHostPort;
import io.panda.application.codehost.review.HandleReviewCommentUseCase;
import io.panda.application.ticketing.port.TicketingPort;
import io.panda.application.workflow.cancel.CancelStaleRunUseCase;
import io.panda.application.workflow.port.WorkflowHolder;
import io.panda.domain.model.codehost.CodeChangeRef;
import io.panda.domain.model.ticketing.ExternalCommentParentType;
import io.panda.domain.model.ticketing.IncomingComment;
import io.panda.domain.model.ticketing.WorkItem;
import io.panda.infrastructure.agent.opencode.OpenCodeRuntimeConfig;
import io.panda.infrastructure.ticketing.jira.JiraConfig;
import io.panda.infrastructure.ticketing.jira.JiraSystem;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GitHubPollingJob {

    private static final Logger LOG = Logger.getLogger(GitHubPollingJob.class);

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP_TYPE = new TypeReference<>() {};
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile("^panda/([A-Za-z]+-\\d+)(?:/.*)?$");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();

    @Inject GitHubConfig config;
    @Inject GitHubTokenProvider tokenProvider;
    @Inject WorkflowHolder workflowHolder;
    @Inject CodeHostPort codeHostPort;
    @Inject TicketingPort ticketingPort;
    @Inject ObjectMapper objectMapper;
    @Inject OpenCodeRuntimeConfig agentRuntimeConfig;
    @Inject JiraConfig jiraConfig;
    @Inject HandleMergedPullRequestUseCase handleMergedPullRequestUseCase;
    @Inject HandleReviewCommentUseCase handleReviewCommentUseCase;
    @Inject CancelStaleRunUseCase cancelStaleRunUseCase;

    @Scheduled(
        every = "${panda.github.poll-interval-minutes:1}m",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    void pollOpenPullRequests() {
        if (config.token().filter(t -> !t.isBlank()).isEmpty() && config.appId().isEmpty()) return;
        List<String> repositories = codeHostPort.configuredRepositories();
        if (repositories.isEmpty()) return;

        for (String repository : repositories) {
            processMergedPullRequests(repository);
        }

        cancelStaleRunUseCase.execute(effectiveStaleRunDurationMinutes());

        if (workflowHolder.isBusy()) {
            LOG.info("Skipping GitHub review comment polling because an agent run is active");
            return;
        }

        for (String repository : repositories) {
            if (workflowHolder.isBusy()) return;
            pollRepositoryForReviewComments(repository);
        }
    }

    // --- Merged PRs ---

    private void processMergedPullRequests(String repository) {
        for (Map<String, Object> pr : fetchRecentlyClosedPullRequests(repository)) {
            if (!isPANDABranch(pr) || !isMerged(pr)) continue;
            String ticketKey = extractTicketKey(extractHeadBranch(pr));
            if (ticketKey == null) continue;

            WorkItem workItem = safeLoadWorkItem(ticketKey);
            if (workItem == null || !jiraConfig.reviewStatus().equalsIgnoreCase(workItem.status())) continue;
            Instant mergedAt = extractInstant(pr.get("merged_at"));
            if (!wasMergedAfterTicketUpdate(workItem, mergedAt)) continue;

            Number prNumber = (Number) pr.get("number");
            String externalId = repository + GitHubCodeHostAdapter.PULL_REQUEST_EXTERNAL_ID_SEPARATOR + prNumber.longValue();

            handleMergedPullRequestUseCase.execute(new HandleMergedPullRequestUseCase.Command(
                JiraSystem.ID, ticketKey, externalId,
                string(pr.get("title")), string(pr.get("html_url")), string(pr.get("body"))
            ));
        }
    }

    // --- Review comments ---

    private void pollRepositoryForReviewComments(String repository) {
        List<Map<String, Object>> pandaPRs = fetchOpenPullRequests(repository).stream()
            .filter(this::isPANDABranch).toList();
        for (Map<String, Object> pr : pandaPRs) {
            if (workflowHolder.isBusy()) return;
            checkForReviewComments(repository, pr);
        }
    }

    private void checkForReviewComments(String repository, Map<String, Object> pr) {
        String headBranch = extractHeadBranch(pr);
        String baseBranch = extractBaseBranch(pr);
        String ticketKey = extractTicketKey(headBranch);
        if (ticketKey == null) return;

        WorkItem workItem = safeLoadWorkItem(ticketKey);
        if (workItem == null || !jiraConfig.reviewStatus().equalsIgnoreCase(workItem.status())) return;

        Number prNumber = (Number) pr.get("number");
        String externalId = repository + GitHubCodeHostAdapter.PULL_REQUEST_EXTERNAL_ID_SEPARATOR + prNumber.longValue();

        String prNumberStr = String.valueOf(prNumber.longValue());
        List<Map<String, Object>> reviewComments = fetchReviewComments(repository, prNumberStr);
        List<Map<String, Object>> issueComments = fetchIssueComments(repository, prNumberStr);
        List<Map<String, Object>> allComments = new java.util.ArrayList<>();
        allComments.addAll(reviewComments);
        allComments.addAll(issueComments);
        if (allComments.isEmpty()) return;

        Instant lastCommitDate = fetchLastCommitDate(repository, headBranch);

        List<IncomingComment> newComments = allComments.stream()
            .filter(this::isHumanComment)
            .filter(c -> {
                Instant created = extractInstant(c.get("created_at"));
                return created != null && (lastCommitDate == null || created.isAfter(lastCommitDate));
            })
            .sorted(Comparator.comparing(c -> extractInstant(c.get("created_at"))))
            .map(c -> toIncomingComment(externalId, c))
            .toList();
        if (newComments.isEmpty()) return;

        LOG.infof("Found %d new review comment(s) on PR %s for ticket %s", newComments.size(), externalId, ticketKey);

        CodeChangeRef codeChange = new CodeChangeRef(
            GitHubSystem.ID, externalId, repository, string(pr.get("html_url")), headBranch, baseBranch
        );

        handleReviewCommentUseCase.execute(new HandleReviewCommentUseCase.Command(
            JiraSystem.ID, ticketKey, repository, codeChange, newComments
        ));
    }

    // --- GitHub HTTP ---

    private List<Map<String, Object>> fetchOpenPullRequests(String repository) {
        return fetchList(config.apiUrl() + "/repos/%s/pulls?state=open&per_page=100".formatted(repository));
    }

    private List<Map<String, Object>> fetchRecentlyClosedPullRequests(String repository) {
        return fetchList(config.apiUrl() + "/repos/%s/pulls?state=closed&sort=updated&direction=desc&per_page=25".formatted(repository));
    }

    private List<Map<String, Object>> fetchReviewComments(String repository, String prNumber) {
        return fetchList(config.apiUrl() + "/repos/%s/pulls/%s/comments?per_page=100&sort=updated&direction=desc".formatted(repository, prNumber));
    }

    private List<Map<String, Object>> fetchIssueComments(String repository, String prNumber) {
        return fetchList(config.apiUrl() + "/repos/%s/issues/%s/comments?per_page=100&sort=updated&direction=desc".formatted(repository, prNumber));
    }

    private Instant fetchLastCommitDate(String repository, String branch) {
        List<Map<String, Object>> commits = fetchList(
            config.apiUrl() + "/repos/%s/commits?sha=%s&per_page=1".formatted(repository, branch));
        if (commits.isEmpty()) return null;
        Map<String, Object> commit = map(commits.getFirst().get("commit"));
        return extractInstant(map(commit.get("committer")).get("date"));
    }

    private List<Map<String, Object>> fetchList(String url) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + tokenProvider.getToken())
            .header("Accept", "application/vnd.github+json")
            .timeout(HTTP_TIMEOUT).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) return List.of();
            return objectMapper.readValue(response.body(), LIST_OF_MAP_TYPE);
        } catch (IOException e) {
            LOG.warnf(e, "GitHub API call failed: %s", url);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during GitHub API call", e);
        }
    }

    // --- Parsing ---

    private boolean isPANDABranch(Map<String, Object> pr) {
        String head = extractHeadBranch(pr);
        return head != null && head.startsWith(GitHubCodeHostAdapter.BRANCH_PREFIX);
    }

    private boolean isMerged(Map<String, Object> pr) {
        return pr.get("merged_at") != null;
    }

    static boolean wasMergedAfterTicketUpdate(WorkItem workItem, Instant mergedAt) {
        if (workItem == null || mergedAt == null) {
            return false;
        }
        Instant ticketUpdatedAt = workItem.updatedAt();
        return ticketUpdatedAt == null || mergedAt.isAfter(ticketUpdatedAt);
    }

    private String extractHeadBranch(Map<String, Object> pr) {
        return string(map(pr.get("head")).get("ref"));
    }

    private String extractBaseBranch(Map<String, Object> pr) {
        return string(map(pr.get("base")).get("ref"));
    }

    private String extractTicketKey(String branchName) {
        if (branchName == null) return null;
        Matcher matcher = TICKET_KEY_PATTERN.matcher(branchName);
        return matcher.matches() ? matcher.group(1).toUpperCase() : null;
    }

    private boolean isHumanComment(Map<String, Object> comment) {
        String login = string(map(comment.get("user")).get("login"));
        return login != null && !login.endsWith("[bot]");
    }

    private IncomingComment toIncomingComment(String externalId, Map<String, Object> comment) {
        Map<String, Object> user = map(comment.get("user"));
        return new IncomingComment(
            string(comment.get("id")), ExternalCommentParentType.CODE_CHANGE.id(),
            externalId, string(user.get("login")), string(comment.get("body")),
            extractInstant(comment.get("created_at")), extractInstant(comment.get("updated_at"))
        );
    }

    private WorkItem safeLoadWorkItem(String ticketKey) {
        try {
            return ticketingPort.loadWorkItem(JiraSystem.ID, ticketKey).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Instant extractInstant(Object value) {
        if (value == null) return null;
        try { return Instant.parse(String.valueOf(value)); }
        catch (DateTimeParseException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long effectiveStaleRunDurationMinutes() {
        return (long) agentRuntimeConfig.hardTimeoutMinutes() + agentRuntimeConfig.staleTimeoutBufferMinutes();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
