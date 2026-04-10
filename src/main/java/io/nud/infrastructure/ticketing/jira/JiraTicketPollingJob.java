package io.nud.infrastructure.ticketing.jira;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nud.application.command.ticketing.CommentWorkItemCommand;
import io.nud.application.command.ticketing.TransitionWorkItemCommand;
import io.nud.application.ticketing.port.TicketingPort;
import io.nud.application.workflow.cancel.CancelStaleRunUseCase;
import io.nud.application.workflow.port.WorkflowHolder;
import io.nud.application.workflow.resume.ResumeWorkflowUseCase;
import io.nud.application.workflow.start.StartInfoCollectionUseCase;
import io.nud.domain.model.ticketing.IncomingComment;
import io.nud.domain.model.ticketing.WorkItem;
import io.nud.domain.model.ticketing.WorkItemTransitionTarget;
import io.nud.domain.model.workflow.WorkflowPhase;
import io.nud.infrastructure.agent.opencode.OpenCodeRuntimeConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.jboss.logging.Logger;

@ApplicationScoped
public class JiraTicketPollingJob {

    private static final Logger LOG = Logger.getLogger(JiraTicketPollingJob.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String SEARCH_ISSUES_PATH = "/rest/api/3/search/jql";
    private static final String MYSELF_PATH = "/rest/api/3/myself";
    private static final String NUD_NOT_ELIGIBLE_MARKER = "NUD marked this ticket as blocked because it is missing:";
    private static final String NUD_COMMENT_MARKER = "NUD marked this ticket as blocked";
    private static final String NUD_READY_FOR_VALIDATION_MARKER = "Pull request merged and ready for validation.";
    private static final long ELIGIBILITY_REASSESSMENT_GRACE_SECONDS = 60;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();
    private String nudAccountId;

    @Inject JiraConfig config;
    @Inject JiraPayloadMapper jiraPayloadMapper;
    @Inject TicketingPort ticketingPort;
    @Inject WorkflowHolder workflowHolder;
    @Inject StartInfoCollectionUseCase startInfoCollectionUseCase;
    @Inject ResumeWorkflowUseCase resumeWorkflowUseCase;
    @Inject CancelStaleRunUseCase cancelStaleRunUseCase;
    @Inject OpenCodeRuntimeConfig agentRuntimeConfig;
    @Inject ObjectMapper objectMapper;

    @Scheduled(
        every = "${nud.jira.poll-interval-minutes:1}m",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    void pollEpicTickets() {
        if (!isPollingConfigured()) {
            return;
        }
        resolveNUDAccountId();
        cancelStaleRunUseCase.execute(effectiveStaleRunDurationMinutes());

        if (workflowHolder.isBusy()) {
            LOG.info("Skipping Jira ticket polling because an agent run is active");
            return;
        }

        pollTodoTickets();
        if (!workflowHolder.isBusy()) {
            pollTicketsWaitingForComment(config.blockedStatus(), WorkflowPhase.INFORMATION_COLLECTION);
        }
        if (!workflowHolder.isBusy()) {
            pollTicketsWaitingForComment(config.validateStatus(), WorkflowPhase.BUSINESS_VALIDATION);
        }
    }

    private void pollTodoTickets() {
        String nextPageToken = null;
        boolean lastPage = false;
        while (!lastPage && !workflowHolder.isBusy()) {
            Map<String, Object> payload = fetchIssuesPage(config.todoStatus(), nextPageToken);
            List<Map<String, Object>> issues = jiraPayloadMapper.extractIssues(payload);
            for (Map<String, Object> issue : issues) {
                if (workflowHolder.isBusy()) return;
                if (tryStartTodoTicket(issue)) return;
            }
            if (issues.isEmpty()) break;
            nextPageToken = jiraPayloadMapper.extractNextPageToken(payload);
            lastPage = jiraPayloadMapper.isLastPage(payload) || nextPageToken == null || nextPageToken.isBlank();
        }
    }

    private boolean tryStartTodoTicket(Map<String, Object> issue) {
        WorkItem workItem = jiraPayloadMapper.toWorkItem(issue);
        if (!workItem.isEligible()) {
            List<IncomingComment> comments = safeLoadComments(workItem.key());
            if (shouldSkipIneligibleTicket(issue, comments)) {
                return false;
            }
            LOG.infof("Ticket %s is not eligible: missing %s", workItem.key(), workItem.missingFields());
            transitionTicket(workItem.key(), WorkItemTransitionTarget.BLOCKED);
            postTicketComment(workItem.key(),
                "NUD marked this ticket as blocked because it is missing: "
                    + String.join(", ", workItem.missingFields())
                    + ". Please add the missing information as a comment on this ticket.");
            return false;
        }

        LOG.infof("Starting agent run for 'To Do' ticket %s (%s)", workItem.key(), workItem.title());
        List<IncomingComment> comments = safeLoadComments(workItem.key());
        startInfoCollectionUseCase.execute(JiraSystem.ID, workItem, comments);
        return true;
    }

    private void pollTicketsWaitingForComment(String status, WorkflowPhase phase) {
        String nextPageToken = null;
        boolean lastPage = false;
        while (!lastPage && !workflowHolder.isBusy()) {
            Map<String, Object> payload = fetchIssuesPage(status, nextPageToken);
            List<Map<String, Object>> issues = jiraPayloadMapper.extractIssues(payload);
            if (issues.isEmpty()) break;
            for (Map<String, Object> issue : issues) {
                if (workflowHolder.isBusy()) return;
                if (tryResumeTicket(issue, phase, status)) return;
            }
            nextPageToken = jiraPayloadMapper.extractNextPageToken(payload);
            lastPage = jiraPayloadMapper.isLastPage(payload) || nextPageToken == null || nextPageToken.isBlank();
        }
    }

    private boolean tryResumeTicket(Map<String, Object> issue, WorkflowPhase phase, String status) {
        WorkItem workItem = jiraPayloadMapper.toWorkItem(issue);
        List<IncomingComment> comments = safeLoadComments(workItem.key());

        boolean hasNewComment = hasNewUserCommentSinceLastNUDComment(comments, this::isNUDComment);
        boolean ticketUpdatedAfterLastComment = !hasNewComment && wasTicketUpdatedAfterLastNUDComment(issue, comments);

        if (!hasNewComment && !ticketUpdatedAfterLastComment) {
            return false;
        }

        String reason = hasNewComment ? "new user comment detected" : "ticket updated after last NUD comment";
        LOG.infof("Resuming ticket %s from status '%s' — %s", workItem.key(), status, reason);
        resumeWorkflowUseCase.execute(JiraSystem.ID, workItem, comments, phase);
        return true;
    }

    private boolean wasTicketUpdatedAfterLastNUDComment(Map<String, Object> issue, List<IncomingComment> comments) {
        Instant issueUpdatedAt = jiraPayloadMapper.extractIssueUpdatedAt(issue);
        if (issueUpdatedAt == null) {
            return false;
        }
        Optional<IncomingComment> lastNUD = comments.stream()
            .filter(this::isNUDComment)
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));
        if (lastNUD.isEmpty()) {
            return false;
        }
        // Grace period to avoid retriggering immediately after NUD itself posts a comment
        Instant threshold = commentTimestamp(lastNUD.get()).plusSeconds(ELIGIBILITY_REASSESSMENT_GRACE_SECONDS);
        return issueUpdatedAt.isAfter(threshold);
    }

    // --- Comment detection ---

    static boolean hasNewUserCommentSinceLastNUDComment(
        List<IncomingComment> comments, Predicate<IncomingComment> isNUDComment
    ) {
        if (comments == null || comments.isEmpty()) return false;
        Optional<IncomingComment> latestNUD = comments.stream()
            .filter(isNUDComment)
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));
        Optional<IncomingComment> latestUser = comments.stream()
            .filter(c -> c != null && c.body() != null && !isNUDComment.test(c))
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));
        if (latestUser.isEmpty()) return false;
        return latestNUD
            .map(d -> commentTimestamp(latestUser.get()).isAfter(commentTimestamp(d)))
            .orElse(true);
    }

    private boolean shouldSkipIneligibleTicket(Map<String, Object> issue, List<IncomingComment> comments) {
        Optional<IncomingComment> lastEligibility = comments.stream()
            .filter(this::isNUDEligibilityComment)
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));
        if (lastEligibility.isEmpty()) return false;
        Instant eligibilityTs = commentTimestamp(lastEligibility.get());
        boolean hasNewerUser = comments.stream()
            .filter(c -> c != null && c.body() != null && !isNUDComment(c))
            .map(JiraTicketPollingJob::commentTimestamp)
            .anyMatch(ts -> ts.isAfter(eligibilityTs));
        if (hasNewerUser) return false;
        Instant issueUpdatedAt = jiraPayloadMapper.extractIssueUpdatedAt(issue);
        return issueUpdatedAt == null
            || !issueUpdatedAt.isAfter(eligibilityTs.plusSeconds(ELIGIBILITY_REASSESSMENT_GRACE_SECONDS));
    }

    private boolean isNUDComment(IncomingComment comment) {
        if (comment == null || comment.body() == null) return false;
        boolean hasMarker = comment.body().contains(NUD_COMMENT_MARKER)
            || comment.body().contains(NUD_NOT_ELIGIBLE_MARKER)
            || comment.body().contains(NUD_READY_FOR_VALIDATION_MARKER);
        if (nudAccountId != null) {
            // Require both: posted by NUD account AND contains a NUD marker.
            // This avoids false positives when the user and NUD share the same account.
            return nudAccountId.equals(comment.authorId()) && hasMarker;
        }
        return hasMarker;
    }

    private boolean isNUDEligibilityComment(IncomingComment comment) {
        return isNUDComment(comment) && comment.body() != null
            && comment.body().contains(NUD_NOT_ELIGIBLE_MARKER);
    }

    private static Instant commentTimestamp(IncomingComment c) {
        return c.updatedAt() != null ? c.updatedAt() : c.createdAt();
    }

    // --- Jira HTTP ---

    private void resolveNUDAccountId() {
        if (nudAccountId != null) return;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + MYSELF_PATH))
            .header("Authorization", "Basic " + encodedCredentials())
            .header("Accept", "application/json")
            .timeout(HTTP_TIMEOUT).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) return;
            Map<String, Object> body = objectMapper.readValue(response.body(), MAP_TYPE);
            Object accountId = body.get("accountId");
            if (accountId instanceof String id && !id.isBlank()) {
                nudAccountId = id;
                LOG.infof("Resolved NUD Jira account ID: %s", nudAccountId);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warnf("Unable to resolve NUD account ID from Jira");
        }
    }

    private Map<String, Object> fetchIssuesPage(String status, String nextPageToken) {
        var node = objectMapper.createObjectNode();
        node.put("jql", "parent = \"%s\" AND status = \"%s\"".formatted(
            escapeJql(config.epicKey()), escapeJql(status)));
        node.put("maxResults", config.pollMaxResults());
        node.putArray("fields").add("summary").add("description").add("status")
            .add("issuetype").add("labels").add("updated");
        if (nextPageToken != null && !nextPageToken.isBlank()) {
            node.put("nextPageToken", nextPageToken);
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + SEARCH_ISSUES_PATH))
            .header("Authorization", "Basic " + encodedCredentials())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(HTTP_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(node.toString())).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Jira polling failed: HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), MAP_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to call Jira", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling Jira", e);
        }
    }

    private List<IncomingComment> safeLoadComments(String ticketKey) {
        try {
            return ticketingPort.listComments(JiraSystem.ID, ticketKey);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to load Jira comments for ticket %s", ticketKey);
            return List.of();
        }
    }

    private void postTicketComment(String ticketKey, String comment) {
        try {
            ticketingPort.comment(new CommentWorkItemCommand(JiraSystem.ID, ticketKey, comment, "ELIGIBILITY_CHECK"));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to post eligibility comment to ticket %s", ticketKey);
        }
    }

    private void transitionTicket(String ticketKey, WorkItemTransitionTarget target) {
        try {
            ticketingPort.transition(new TransitionWorkItemCommand(JiraSystem.ID, ticketKey, target, "ELIGIBILITY_CHECK"));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to transition ticket %s to %s", ticketKey, target);
        }
    }

    private String escapeJql(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String encodedCredentials() {
        return Base64.getEncoder().encodeToString(
            (config.userEmail() + ":" + config.apiToken()).getBytes(StandardCharsets.UTF_8));
    }

    private boolean isPollingConfigured() {
        return !isBlank(config.baseUrl()) && !isBlank(config.userEmail())
            && !isBlank(config.apiToken()) && !isBlank(config.epicKey());
    }

    private long effectiveStaleRunDurationMinutes() {
        return (long) agentRuntimeConfig.hardTimeoutMinutes() + agentRuntimeConfig.staleTimeoutBufferMinutes();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
