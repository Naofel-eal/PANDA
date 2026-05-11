package io.panda.infrastructure.ticketing.jira;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.panda.application.workflow.recovery.RecoverOrphanedTicketsUseCase;
import io.panda.domain.model.ticketing.WorkItem;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OrphanedTicketRecoveryJob {

    private static final Logger LOG = Logger.getLogger(OrphanedTicketRecoveryJob.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String SEARCH_ISSUES_PATH = "/rest/api/3/search/jql";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();

    @Inject
    JiraConfig config;

    @Inject
    JiraPayloadMapper jiraPayloadMapper;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecoverOrphanedTicketsUseCase recoverOrphanedTicketsUseCase;

    void onStart(@Observes StartupEvent event) {
        if (!isConfigured()) {
            LOG.info("Orphaned ticket recovery skipped: Jira not fully configured");
            return;
        }

        try {
            List<WorkItem> orphaned = findOrphanedTickets();
            recoverOrphanedTicketsUseCase.execute(JiraSystem.ID, orphaned);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Orphaned ticket recovery failed at startup — tickets may need manual intervention");
        }
    }

    private List<WorkItem> findOrphanedTickets() {
        String accountId = config.serviceAccountId().orElse("");
        String jql = "project = \"%s\" AND assignee = \"%s\" AND status = \"%s\"".formatted(
            escapeJql(config.projectKey()), escapeJql(accountId), escapeJql(config.inProgressStatus()));

        var node = objectMapper.createObjectNode();
        node.put("jql", jql);
        node.put("maxResults", 50);
        node.putArray("fields").add("summary").add("description").add("status")
            .add("issuetype").add("labels").add("updated");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + SEARCH_ISSUES_PATH))
            .header("Authorization", "Bearer " + config.apiToken())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(HTTP_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(node.toString()))
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOG.warnf("Jira orphaned ticket search failed: HTTP %d", response.statusCode());
                return List.of();
            }
            Map<String, Object> payload = objectMapper.readValue(response.body(), MAP_TYPE);
            List<Map<String, Object>> issues = jiraPayloadMapper.extractIssues(payload);
            LOG.infof("Found %d ticket(s) in '%s' assigned to PANDA at startup",
                issues.size(), config.inProgressStatus());
            return issues.stream().map(jiraPayloadMapper::toWorkItem).toList();
        } catch (IOException e) {
            LOG.warnf(e, "Unable to search Jira for orphaned tickets");
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private boolean isConfigured() {
        return config.baseUrl() != null && !config.baseUrl().isBlank()
            && config.apiToken() != null && !config.apiToken().isBlank()
            && config.serviceAccountId().filter(id -> !id.isBlank()).isPresent();
    }

    private static String escapeJql(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
