package io.devflow.infrastructure.ticketing.jira;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.application.command.workflow.WorkflowSignalCommand;
import io.devflow.application.usecase.WorkflowSignalService;
import io.devflow.domain.ticketing.WorkItem;
import io.devflow.domain.workflow.WorkflowSignalType;
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
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

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
    private static final String ISSUE_FIELDS = "summary,description,status,issuetype,labels,updated";
    private static final String SIGNAL_ID_PREFIX = "jira-poll:issue:";
    private static final String PAYLOAD_JQL = "jql";
    private static final String PAYLOAD_FIELDS = "fields";
    private static final String PAYLOAD_MAX_RESULTS = "maxResults";
    private static final String PAYLOAD_NEXT_PAGE_TOKEN = "nextPageToken";
    private static final String JQL_PARENT_TEMPLATE = "parent = \"%s\" AND status = \"%s\"";

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT)
        .build();

    @Inject
    JiraConfig config;

    @Inject
    JiraPayloadMapper jiraPayloadMapper;

    @Inject
    WorkflowSignalService workflowSignalService;

    @Inject
    ObjectMapper objectMapper;

    @Scheduled(
        every = "${devflow.jira.poll-interval-minutes:1}m",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    void pollEpicTickets() {
        if (!isPollingConfigured()) {
            return;
        }

        LOG.infof(
            "Polling Jira epic %s for tickets in status '%s'",
            config.epicKey(),
            config.todoStatus()
        );
        String nextPageToken = null;
        boolean lastPage = false;
        while (!lastPage) {
            Map<String, Object> payload = fetchIssuesPage(nextPageToken);
            List<Map<String, Object>> issues = jiraPayloadMapper.extractIssues(payload);
            LOG.infof(
                "Fetched %d Jira tickets from epic %s (nextPageToken=%s)",
                issues.size(),
                config.epicKey(),
                nextPageToken == null ? "<initial>" : nextPageToken
            );

            issues.forEach(this::dispatchIssueSignal);

            if (issues.isEmpty()) {
                break;
            }
            nextPageToken = jiraPayloadMapper.extractNextPageToken(payload);
            lastPage = jiraPayloadMapper.isLastPage(payload) || nextPageToken == null || nextPageToken.isBlank();
        }
    }

    private boolean isPollingConfigured() {
        return !isBlank(config.baseUrl())
            && !isBlank(config.userEmail())
            && !isBlank(config.apiToken())
            && !isBlank(config.epicKey());
    }

    private void dispatchIssueSignal(Map<String, Object> issue) {
        WorkItem workItem = jiraPayloadMapper.toWorkItem(issue);
        Instant occurredAt = jiraPayloadMapper.extractIssueUpdatedAt(issue);
        String sourceEventId = buildSourceEventId(workItem.key(), occurredAt);
        LOG.infof(
            "Dispatching Jira ticket %s (%s) from epic %s to workflow engine",
            workItem.key(),
            workItem.title(),
            config.epicKey()
        );

        workflowSignalService.handle(new WorkflowSignalCommand(
            WorkflowSignalType.WORK_ITEM_UPDATED,
            JiraSystem.ID,
            sourceEventId,
            null,
            occurredAt,
            workItem,
            null,
            null,
            null,
            null
        ));
    }

    private String buildSourceEventId(String workItemKey, Instant occurredAt) {
        return SIGNAL_ID_PREFIX + workItemKey + ":" + (occurredAt == null ? "unknown" : occurredAt.toString());
    }

    private Map<String, Object> fetchIssuesPage(String nextPageToken) {
        var payloadNode = objectMapper.createObjectNode();
        payloadNode.put(PAYLOAD_JQL, buildJql());
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
            .uri(buildIssuesUri())
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BASIC + encodedCredentials())
            .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .timeout(HTTP_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Jira issue polling failed: HTTP " + response.statusCode() + " - " + response.body());
            }
            return objectMapper.readValue(response.body(), MAP_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to call Jira for ticket polling", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling Jira", exception);
        }
    }

    private URI buildIssuesUri() {
        return URI.create(config.baseUrl() + SEARCH_ISSUES_PATH);
    }

    private String buildJql() {
        return JQL_PARENT_TEMPLATE.formatted(
            escapeJqlValue(config.epicKey()),
            escapeJqlValue(config.todoStatus())
        );
    }

    private String escapeJqlValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String encodedCredentials() {
        String credentials = config.userEmail() + ":" + config.apiToken();
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
