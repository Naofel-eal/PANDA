package io.devflow.infrastructure.ticketing.jira;

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
import io.devflow.application.service.EligibilityService;
import io.devflow.application.service.WorkspaceLayoutService;
import io.devflow.domain.ticketing.IncomingComment;
import io.devflow.domain.ticketing.WorkItem;
import io.devflow.domain.workflow.WorkflowPhase;
import io.devflow.domain.workspace.PreparedWorkspace;
import io.devflow.domain.workspace.RepositoryWorkspace;
import io.devflow.infrastructure.agent.opencode.OpenCodeRuntimeConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.jboss.logging.Logger;

/**
 * Stateless Jira ticket poller. Uses DevFlowRuntime (volatile currentRun) instead of
 * WorkflowStore/AgentRunStore. Dispatches agent runs directly (no outbox, no signal service).
 *
 * <p>Poll cycle:
 * <ol>
 *   <li>If busy → skip entirely</li>
 *   <li>Search epic for "To Do" tickets → pick first eligible → start info-collection agent run</li>
 *   <li>If not busy after step 2, search epic for "Blocked" and "To Validate" tickets →
 *       check for new user comments → start agent</li>
 * </ol>
 */
@ApplicationScoped
public class JiraTicketPollingJob {

    private static final Logger LOG = Logger.getLogger(JiraTicketPollingJob.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String AUTH_SCHEME_BASIC = "Basic ";
    private static final String SEARCH_ISSUES_PATH = "/rest/api/3/search/jql";
    private static final String MYSELF_PATH = "/rest/api/3/myself";
    private static final String PAYLOAD_JQL = "jql";
    private static final String PAYLOAD_FIELDS = "fields";
    private static final String PAYLOAD_MAX_RESULTS = "maxResults";
    private static final String PAYLOAD_NEXT_PAGE_TOKEN = "nextPageToken";
    private static final String JQL_PARENT_STATUS_TEMPLATE = "parent = \"%s\" AND status = \"%s\"";
    private static final String DEVFLOW_COMMENT_MARKER = "Devflow marked this ticket as blocked";
    private static final String DEVFLOW_NOT_ELIGIBLE_MARKER = "Devflow marked this ticket as blocked because it is missing:";
    private static final String DEVFLOW_READY_FOR_VALIDATION_MARKER = "Pull request merged and ready for validation.";
    private static final long ELIGIBILITY_REASSESSMENT_GRACE_SECONDS = 60;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT)
        .build();

    private String devflowAccountId;

    @Inject
    JiraConfig config;

    @Inject
    JiraPayloadMapper jiraPayloadMapper;

    @Inject
    TicketingPort ticketingPort;

    @Inject
    DevFlowRuntime runtime;

    @Inject
    AgentRuntimePort agentRuntimePort;

    @Inject
    CodeHostPort codeHostPort;

    @Inject
    WorkspaceLayoutService workspaceLayoutService;

    @Inject
    EligibilityService eligibilityService;

    @Inject
    OpenCodeRuntimeConfig agentRuntimeConfig;

    @Inject
    ObjectMapper objectMapper;

    // ---- Scheduled entry point ----

    @Scheduled(
        every = "${devflow.jira.poll-interval-minutes:1}m",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    void pollEpicTickets() {
        if (!isPollingConfigured()) {
            return;
        }
        resolveDevflowAccountId();
        cancelStaleRunIfNeeded();
        if (runtime.isBusy()) {
            LOG.info("Skipping Jira ticket polling because an agent run is active");
            return;
        }

        LOG.infof("Polling Jira epic %s for tickets in status '%s'", config.epicKey(), config.todoStatus());
        pollTodoTickets();

        if (!runtime.isBusy()) {
            pollBlockedTickets();
        }
        if (!runtime.isBusy()) {
            pollValidationTickets();
        }
    }

    // ---- Stale run detection ----

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

    // ---- "To Do" ticket handling ----

    private void pollTodoTickets() {
        String nextPageToken = null;
        boolean lastPage = false;

        while (!lastPage && !runtime.isBusy()) {
            Map<String, Object> payload = fetchIssuesPage(config.todoStatus(), nextPageToken);
            List<Map<String, Object>> issues = jiraPayloadMapper.extractIssues(payload);
            LOG.infof(
                "Fetched %d Jira 'To Do' tickets from epic %s (nextPageToken=%s)",
                issues.size(), config.epicKey(),
                nextPageToken == null ? "<initial>" : nextPageToken
            );

            for (Map<String, Object> issue : issues) {
                if (runtime.isBusy()) {
                    return;
                }
                if (tryStartTodoTicket(issue)) {
                    return;
                }
            }

            if (issues.isEmpty()) {
                break;
            }
            nextPageToken = jiraPayloadMapper.extractNextPageToken(payload);
            lastPage = jiraPayloadMapper.isLastPage(payload)
                || nextPageToken == null
                || nextPageToken.isBlank();
        }
    }

    private boolean tryStartTodoTicket(Map<String, Object> issue) {
        WorkItem workItem = jiraPayloadMapper.toWorkItem(issue);
        EligibilityService.EligibilityDecision decision = eligibilityService.evaluate(workItem);
        if (!decision.hasEnoughInformation()) {
            List<IncomingComment> comments = safeLoadComments(workItem.key());
            if (shouldSkipIneligibleTicket(issue, comments)) {
                LOG.debugf(
                    "Ticket %s still not eligible and no new information since last assessment, skipping",
                    workItem.key()
                );
                return false;
            }
            LOG.infof(
                "Ticket %s is not eligible: missing %s",
                workItem.key(), decision.missingFields()
            );
            postTicketComment(
                workItem.key(),
                "Devflow marked this ticket as blocked because it is missing: "
                    + String.join(", ", decision.missingFields())
                    + ". Please add the missing information and move the ticket back to '"
                    + config.todoStatus() + "'."
            );
            return false;
        }

        LOG.infof("Starting agent run for 'To Do' ticket %s (%s)", workItem.key(), workItem.title());
        List<IncomingComment> comments = safeLoadComments(workItem.key());
        startAgentRun(workItem, comments, WorkflowPhase.INFORMATION_COLLECTION);
        return true;
    }

    // ---- "Blocked" ticket handling ----

    private void pollBlockedTickets() {
        pollTicketsWaitingForComment(config.blockedStatus(), WorkflowPhase.INFORMATION_COLLECTION);
    }

    private void pollValidationTickets() {
        pollTicketsWaitingForComment(config.validateStatus(), WorkflowPhase.IMPLEMENTATION);
    }

    private void pollTicketsWaitingForComment(String status, WorkflowPhase phase) {
        String nextPageToken = null;
        boolean lastPage = false;

        while (!lastPage && !runtime.isBusy()) {
            Map<String, Object> payload = fetchIssuesPage(status, nextPageToken);
            List<Map<String, Object>> issues = jiraPayloadMapper.extractIssues(payload);
            if (issues.isEmpty()) {
                break;
            }
            LOG.infof("Fetched %d Jira '%s' tickets from epic %s", issues.size(), status, config.epicKey());

            for (Map<String, Object> issue : issues) {
                if (runtime.isBusy()) {
                    return;
                }
                if (tryResumeTicket(issue, phase, status)) {
                    return;
                }
            }

            nextPageToken = jiraPayloadMapper.extractNextPageToken(payload);
            lastPage = jiraPayloadMapper.isLastPage(payload)
                || nextPageToken == null
                || nextPageToken.isBlank();
        }
    }

    private boolean tryResumeTicket(Map<String, Object> issue, WorkflowPhase phase, String status) {
        WorkItem workItem = jiraPayloadMapper.toWorkItem(issue);
        List<IncomingComment> comments = safeLoadComments(workItem.key());
        if (!hasNewUserCommentSinceLastDevflowComment(comments, this::isDevflowComment)) {
            return false;
        }

        LOG.infof(
            "Resuming Jira ticket %s from status '%s' — new user comment detected after last DevFlow comment",
            workItem.key(), status
        );
        startAgentRun(workItem, comments, phase);
        return true;
    }

    // ---- Agent run dispatch (direct, no outbox) ----

    private void startAgentRun(WorkItem workItem, List<IncomingComment> comments, WorkflowPhase phase) {
        UUID workflowId = UUID.randomUUID();
        UUID agentRunId = UUID.randomUUID();
        String objective = "Analyze and implement work item " + workItem.key();

        runtime.startRun(workflowId, agentRunId, JiraSystem.ID, workItem.key(), phase, objective);

        Map<String, Object> snapshot = buildSnapshot(workflowId, workItem, comments);
        workspaceLayoutService.ensureDirectories(workflowId);

        Map<String, Object> preparedSnapshot = prepareSnapshotWithWorkspace(workflowId, workItem, snapshot);
        preparedSnapshot.put("phase", phase.name());

        StartAgentRunCommand command = StartAgentRunCommand.start(
            UUID.randomUUID(),
            workflowId,
            agentRunId,
            phase,
            objective,
            preparedSnapshot
        );

        try {
            LOG.infof("Dispatching agent run %s for ticket %s, phase %s", agentRunId, workItem.key(), phase);
            agentRuntimePort.startRun(command);
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Failed to dispatch agent run for ticket %s", workItem.key());
            runtime.clearRun();
        }
    }

    private Map<String, Object> buildSnapshot(UUID workflowId, WorkItem workItem, List<IncomingComment> comments) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowId", workflowId.toString());
        snapshot.put("workItemSystem", JiraSystem.ID);
        snapshot.put("workItemKey", workItem.key());
        snapshot.put("workItem", workItem);
        if (!comments.isEmpty()) {
            snapshot.put("workItemComments", comments);
        }
        return snapshot;
    }

    private Map<String, Object> prepareSnapshotWithWorkspace(
        UUID workflowId,
        WorkItem workItem,
        Map<String, Object> inputSnapshot
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>(inputSnapshot);
        List<String> repositories = workItem.repositories().isEmpty()
            ? codeHostPort.configuredRepositories()
            : workItem.repositories();

        PreparedWorkspace preparedWorkspace = codeHostPort.prepareWorkspace(
            new PrepareWorkspaceCommand(workflowId, repositories, Map.of())
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

    // ---- Comment detection helpers ----

    private boolean isDevflowComment(IncomingComment comment) {
        if (comment == null) {
            return false;
        }
        if (devflowAccountId != null) {
            return devflowAccountId.equals(comment.authorId());
        }
        return comment.body() != null && (
            comment.body().contains(DEVFLOW_COMMENT_MARKER)
                || comment.body().contains(DEVFLOW_NOT_ELIGIBLE_MARKER)
                || comment.body().contains(DEVFLOW_READY_FOR_VALIDATION_MARKER)
        );
    }

    private boolean isUserComment(IncomingComment comment) {
        return comment != null && comment.body() != null && !isDevflowComment(comment);
    }

    private static Instant commentTimestamp(IncomingComment comment) {
        return comment.updatedAt() != null ? comment.updatedAt() : comment.createdAt();
    }

    static boolean hasNewUserCommentSinceLastDevflowComment(
        List<IncomingComment> comments,
        Predicate<IncomingComment> isDevflowComment
    ) {
        if (comments == null || comments.isEmpty()) {
            return false;
        }

        Optional<IncomingComment> latestDevflowComment = comments.stream()
            .filter(isDevflowComment)
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));

        Optional<IncomingComment> latestUserComment = comments.stream()
            .filter(comment -> comment != null && comment.body() != null && !isDevflowComment.test(comment))
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));

        if (latestUserComment.isEmpty()) {
            return false;
        }

        return latestDevflowComment
            .map(devflowComment -> commentTimestamp(latestUserComment.get()).isAfter(commentTimestamp(devflowComment)))
            .orElse(true);
    }

    /**
     * Determines whether an ineligible ticket should be silently skipped because DevFlow already
     * posted an eligibility comment and no new information has been added since then.
     *
     * <p>Re-evaluation is triggered when:
     * <ul>
     *   <li>A non-DevFlow user posted a comment after the last eligibility assessment</li>
     *   <li>The issue was updated (description, fields) after the assessment (with a grace period
     *       to ignore the update caused by the assessment comment itself)</li>
     * </ul>
     */
    private boolean shouldSkipIneligibleTicket(Map<String, Object> issue, List<IncomingComment> comments) {
        Optional<IncomingComment> lastEligibilityComment = comments.stream()
            .filter(this::isDevflowEligibilityComment)
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));

        if (lastEligibilityComment.isEmpty()) {
            return false;
        }

        Instant eligibilityTimestamp = commentTimestamp(lastEligibilityComment.get());

        boolean hasNewerUserComment = comments.stream()
            .filter(this::isUserComment)
            .map(JiraTicketPollingJob::commentTimestamp)
            .anyMatch(timestamp -> timestamp.isAfter(eligibilityTimestamp));

        if (hasNewerUserComment) {
            return false;
        }

        Instant issueUpdatedAt = jiraPayloadMapper.extractIssueUpdatedAt(issue);
        if (issueUpdatedAt != null
            && issueUpdatedAt.isAfter(eligibilityTimestamp.plusSeconds(ELIGIBILITY_REASSESSMENT_GRACE_SECONDS))) {
            return false;
        }

        return true;
    }

    private boolean isDevflowEligibilityComment(IncomingComment comment) {
        return isDevflowComment(comment)
            && comment.body() != null
            && comment.body().contains(DEVFLOW_NOT_ELIGIBLE_MARKER);
    }

    private long effectiveStaleRunDurationMinutes() {
        return (long) agentRuntimeConfig.hardTimeoutMinutes() + agentRuntimeConfig.staleTimeoutBufferMinutes();
    }

    // ---- Jira API helpers ----

    private void resolveDevflowAccountId() {
        if (devflowAccountId != null) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + MYSELF_PATH))
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BASIC + encodedCredentials())
            .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOG.warnf(
                    "Failed to resolve DevFlow account ID: HTTP %d - %s",
                    response.statusCode(),
                    response.body().length() > 200 ? response.body().substring(0, 200) : response.body()
                );
                return;
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), MAP_TYPE);
            Object accountId = body.get("accountId");
            if (accountId instanceof String id && !id.isBlank()) {
                devflowAccountId = id;
                LOG.infof("Resolved DevFlow Jira account ID: %s", devflowAccountId);
            } else {
                LOG.warnf("Jira /myself response did not contain a valid accountId");
            }
        } catch (IOException exception) {
            LOG.warnf(exception, "Unable to resolve DevFlow account ID from Jira");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOG.warnf("Interrupted while resolving DevFlow account ID");
        }
    }

    private List<IncomingComment> safeLoadComments(String ticketKey) {
        try {
            return ticketingPort.listComments(JiraSystem.ID, ticketKey);
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Unable to load Jira comments for ticket %s", ticketKey);
            return List.of();
        }
    }

    private void postTicketComment(String ticketKey, String comment) {
        try {
            ticketingPort.comment(new CommentWorkItemCommand(JiraSystem.ID, ticketKey, comment, "ELIGIBILITY_CHECK"));
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Failed to post eligibility comment to ticket %s", ticketKey);
        }
    }

    private Map<String, Object> fetchIssuesPage(String status, String nextPageToken) {
        var payloadNode = objectMapper.createObjectNode();
        payloadNode.put(PAYLOAD_JQL, buildJql(status));
        payloadNode.put(PAYLOAD_MAX_RESULTS, config.pollMaxResults());
        payloadNode.putArray(PAYLOAD_FIELDS)
            .add("summary")
            .add("description")
            .add("status")
            .add("issuetype")
            .add("labels")
            .add("updated");
        if (nextPageToken != null && !nextPageToken.isBlank()) {
            payloadNode.put(PAYLOAD_NEXT_PAGE_TOKEN, nextPageToken);
        }
        String payload = payloadNode.toString();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(buildSearchUri())
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BASIC + encodedCredentials())
            .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .timeout(HTTP_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                    "Jira issue polling failed: HTTP " + response.statusCode() + " - " + response.body()
                );
            }
            return objectMapper.readValue(response.body(), MAP_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to call Jira for ticket polling", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling Jira", exception);
        }
    }

    private URI buildSearchUri() {
        return URI.create(config.baseUrl() + SEARCH_ISSUES_PATH);
    }

    private String buildJql(String status) {
        return JQL_PARENT_STATUS_TEMPLATE.formatted(
            escapeJqlValue(config.epicKey()),
            escapeJqlValue(status)
        );
    }

    private String escapeJqlValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String encodedCredentials() {
        String credentials = config.userEmail() + ":" + config.apiToken();
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isPollingConfigured() {
        return !isBlank(config.baseUrl())
            && !isBlank(config.userEmail())
            && !isBlank(config.apiToken())
            && !isBlank(config.epicKey());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
