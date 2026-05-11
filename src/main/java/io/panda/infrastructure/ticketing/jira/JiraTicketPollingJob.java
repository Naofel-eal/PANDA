package io.panda.infrastructure.ticketing.jira;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.panda.application.command.ticketing.CommentWorkItemCommand;
import io.panda.application.command.ticketing.TransitionWorkItemCommand;
import io.panda.application.ticketing.port.TicketingPort;
import io.panda.application.workflow.cancel.CancelStaleRunUseCase;
import io.panda.application.workflow.port.WorkflowHolder;
import io.panda.application.workflow.resume.ResumeWorkflowUseCase;
import io.panda.application.workflow.start.StartInfoCollectionUseCase;
import io.panda.domain.model.ticketing.IncomingComment;
import io.panda.domain.model.ticketing.WorkItem;
import io.panda.domain.model.ticketing.WorkItemTransitionTarget;
import io.panda.domain.model.workflow.WorkflowPhase;
import io.panda.infrastructure.agent.opencode.OpenCodeRuntimeConfig;
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
    private static final String PANDA_NOT_ELIGIBLE_MARKER = "PANDA marked this ticket as blocked because it is missing:";
    private static final String PANDA_COMMENT_MARKER = "PANDA marked this ticket as blocked";
    private static final String PANDA_READY_FOR_VALIDATION_MARKER = "Pull request merged and ready for validation.";
    private static final long ELIGIBILITY_REASSESSMENT_GRACE_SECONDS = 60;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();
    private String pandaAccountId;

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
        every = "${panda.jira.poll-interval-minutes:1}m",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    void pollEpicTickets() {
        if (!isPollingConfigured()) {
            return;
        }
        resolvePANDAAccountId();
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
            pollTicketsWaitingForComment(config.validateStatus(), WorkflowPhase.INFORMATION_COLLECTION);
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
                "PANDA marked this ticket as blocked because it is missing: "
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

    private boolean tryResumeTicket(Map<String, Object> issue, WorkflowPhase phase, String expectedStatus) {
        WorkItem workItem = jiraPayloadMapper.toWorkItem(issue);
        List<IncomingComment> comments = safeLoadComments(workItem.key());

        boolean hasNewComment = hasNewUserCommentSinceLastPANDAComment(comments, this::isPANDAComment);
        boolean ticketUpdatedAfterLastComment = !hasNewComment && wasTicketUpdatedAfterLastPANDAComment(issue, comments);

        if (!hasNewComment && !ticketUpdatedAfterLastComment) {
            return false;
        }

        // Re-verify current status before resuming
        WorkItem freshTicket = safeLoadWorkItem(JiraSystem.ID, workItem.key());
        if (freshTicket != null && !expectedStatus.equalsIgnoreCase(freshTicket.status())) {
            LOG.infof("Ticket %s status changed to %s since poll started; skipping resume", workItem.key(), freshTicket.status());
            return false;
        }

        String reason = hasNewComment ? "new user comment detected" : "ticket updated after last PANDA comment";
        LOG.infof("Resuming ticket %s from status '%s' — %s", workItem.key(), expectedStatus, reason);
        resumeWorkflowUseCase.execute(JiraSystem.ID, workItem, comments, phase);
        return true;
    }

    private boolean wasTicketUpdatedAfterLastPANDAComment(Map<String, Object> issue, List<IncomingComment> comments) {
        Instant issueUpdatedAt = jiraPayloadMapper.extractIssueUpdatedAt(issue);
        if (issueUpdatedAt == null) {
            return false;
        }
        Optional<IncomingComment> lastPANDA = comments.stream()
            .filter(this::isPANDAComment)
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));
        if (lastPANDA.isEmpty()) {
            return false;
        }
        // Grace period to avoid retriggering immediately after PANDA itself posts a comment
        Instant threshold = commentTimestamp(lastPANDA.get()).plusSeconds(ELIGIBILITY_REASSESSMENT_GRACE_SECONDS);
        return issueUpdatedAt.isAfter(threshold);
    }

    // --- Comment detection ---

    static boolean hasNewUserCommentSinceLastPANDAComment(
        List<IncomingComment> comments, Predicate<IncomingComment> isPANDAComment
    ) {
        if (comments == null || comments.isEmpty()) return false;
        Optional<IncomingComment> latestPANDA = comments.stream()
            .filter(isPANDAComment)
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));
        Optional<IncomingComment> latestUser = comments.stream()
            .filter(c -> c != null && c.body() != null && !isPANDAComment.test(c))
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));
        if (latestUser.isEmpty()) return false;
        return latestPANDA
            .map(d -> commentTimestamp(latestUser.get()).isAfter(commentTimestamp(d)))
            .orElse(true);
    }

    private boolean shouldSkipIneligibleTicket(Map<String, Object> issue, List<IncomingComment> comments) {
        Optional<IncomingComment> lastEligibility = comments.stream()
            .filter(this::isPANDAEligibilityComment)
            .max(Comparator.comparing(JiraTicketPollingJob::commentTimestamp));
        if (lastEligibility.isEmpty()) return false;
        Instant eligibilityTs = commentTimestamp(lastEligibility.get());
        boolean hasNewerUser = comments.stream()
            .filter(c -> c != null && c.body() != null && !isPANDAComment(c))
            .map(JiraTicketPollingJob::commentTimestamp)
            .anyMatch(ts -> ts.isAfter(eligibilityTs));
        if (hasNewerUser) return false;
        Instant issueUpdatedAt = jiraPayloadMapper.extractIssueUpdatedAt(issue);
        return issueUpdatedAt == null
            || !issueUpdatedAt.isAfter(eligibilityTs.plusSeconds(ELIGIBILITY_REASSESSMENT_GRACE_SECONDS));
    }

    private boolean isPANDAComment(IncomingComment comment) {
        if (comment == null || comment.body() == null) return false;
        String authorId = comment.authorId();
        if (pandaAccountId != null && !pandaAccountId.isBlank()) {
            if (pandaAccountId.equals(authorId)) {
                return true;
            }
        }
        String body = comment.body();
        return body != null && body.contains("[PANDA");
    }

    private boolean isPANDAEligibilityComment(IncomingComment comment) {
        return isPANDAComment(comment) && comment.body() != null
            && comment.body().contains(PANDA_NOT_ELIGIBLE_MARKER);
    }

    private static Instant commentTimestamp(IncomingComment c) {
        return c.updatedAt() != null ? c.updatedAt() : c.createdAt();
    }

    // --- Jira HTTP ---

    private void resolvePANDAAccountId() {
        config.serviceAccountId().filter(id -> !id.isBlank()).ifPresent(id -> {
            this.pandaAccountId = id;
            LOG.infof("PANDA account ID from config: %s", id);
        });
    }

    private Map<String, Object> fetchIssuesPage(String status, String nextPageToken) {
        var node = objectMapper.createObjectNode();
        node.put("jql", "project = \"%s\" AND assignee = \"%s\" AND status = \"%s\"".formatted(
            escapeJql(config.projectKey()), escapeJql(pandaAccountId != null ? pandaAccountId : ""), escapeJql(status)));
        node.put("maxResults", config.pollMaxResults());
        node.putArray("fields").add("summary").add("description").add("status")
            .add("issuetype").add("labels").add("updated");
        if (nextPageToken != null && !nextPageToken.isBlank()) {
            node.put("nextPageToken", nextPageToken);
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + SEARCH_ISSUES_PATH))
            .header("Authorization", "Bearer " + config.apiToken())
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

    private WorkItem safeLoadWorkItem(String workItemSystem, String workItemKey) {
        try {
            return ticketingPort.loadWorkItem(workItemSystem, workItemKey).orElse(null);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to load Jira work item %s", workItemKey);
            return null;
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

    private boolean isPollingConfigured() {
        return !isBlank(config.baseUrl()) && !isBlank(config.apiToken())
            && config.serviceAccountId().filter(id -> !id.isBlank()).isPresent();
    }

    private long effectiveStaleRunDurationMinutes() {
        return (long) agentRuntimeConfig.hardTimeoutMinutes() + agentRuntimeConfig.staleTimeoutBufferMinutes();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
