package io.devflow.infrastructure.ticketing.jira;

import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.command.ticketing.TransitionWorkItemCommand;
import io.devflow.application.port.support.JsonCodec;
import io.devflow.application.port.ticketing.TicketingPort;
import io.devflow.domain.exception.DomainException;
import io.devflow.domain.ticketing.IncomingComment;
import io.devflow.domain.ticketing.WorkItem;
import io.devflow.domain.ticketing.WorkItemTransitionTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import org.jboss.logging.Logger;

@ApplicationScoped
public class JiraTicketingAdapter implements TicketingPort {

    private static final Logger LOG = Logger.getLogger(JiraTicketingAdapter.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String AUTH_SCHEME_BASIC = "Basic ";
    private static final String API_ISSUE_PATH_TEMPLATE = "/rest/api/3/issue/%s";
    private static final String API_COMMENT_PATH_TEMPLATE = "/rest/api/3/issue/%s/comment";
    private static final String API_TRANSITIONS_PATH_TEMPLATE = "/rest/api/3/issue/%s/transitions";
    private static final String QUERY_WORK_ITEM_FIELDS = "?fields=summary,description,status,issuetype,labels,updated";
    private static final String QUERY_FIELDS_STATUS = "?fields=status";
    private static final String QUERY_COMMENT_PAGE_TEMPLATE = "?startAt=%d&maxResults=%d";
    private static final String ADF_BODY = "body";
    private static final String ADF_TYPE = "type";
    private static final String ADF_DOC = "doc";
    private static final String ADF_VERSION = "version";
    private static final int ADF_VERSION_VALUE = 1;
    private static final String ADF_CONTENT = "content";
    private static final String PAYLOAD_FIELDS = "fields";
    private static final String PAYLOAD_STATUS = "status";
    private static final String PAYLOAD_NAME = "name";
    private static final String PAYLOAD_TRANSITIONS = "transitions";
    private static final String PAYLOAD_TRANSITION = "transition";
    private static final String PAYLOAD_ID = "id";
    private static final String PAYLOAD_START_AT = "startAt";
    private static final String PAYLOAD_MAX_RESULTS = "maxResults";
    private static final String PAYLOAD_TOTAL = "total";
    private static final String PAYLOAD_TO = "to";

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT)
        .build();

    @Inject
    JiraConfig config;

    @Inject
    JsonCodec jsonCodec;

    @Inject
    JiraPayloadMapper jiraPayloadMapper;

    @Override
    public void comment(CommentWorkItemCommand command) {
        if (!JiraSystem.matches(command.workItemSystem())) {
            throw new IllegalArgumentException("Unsupported ticketing system: " + command.workItemSystem());
        }

        LOG.infof("Posting Jira comment on ticket %s", command.workItemKey());
        String credentials = config.userEmail() + ":" + config.apiToken();
        String auth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        String payload = jsonCodec.toJson(buildCommentPayload(command.comment()));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + API_COMMENT_PATH_TEMPLATE.formatted(command.workItemKey())))
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BASIC + auth)
            .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .timeout(HTTP_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new DomainException("Jira comment failed: HTTP " + response.statusCode() + " - " + response.body());
            }
            LOG.infof("Posted Jira comment on ticket %s", command.workItemKey());
        } catch (IOException exception) {
            throw new DomainException("Unable to call Jira", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DomainException("Interrupted while calling Jira", exception);
        }
    }

    @Override
    public void transition(TransitionWorkItemCommand command) {
        if (!JiraSystem.matches(command.workItemSystem())) {
            throw new IllegalArgumentException("Unsupported ticketing system: " + command.workItemSystem());
        }

        String targetStatus = resolveTargetStatus(command.target());
        LOG.infof("Transitioning Jira ticket %s to status %s", command.workItemKey(), targetStatus);
        String currentStatus = currentStatus(command.workItemKey());
        if (matchesStatus(currentStatus, targetStatus)) {
            LOG.infof("Skipping Jira transition for ticket %s because it is already in status %s", command.workItemKey(), targetStatus);
            return;
        }

        String transitionId = resolveTransitionId(command.workItemKey(), targetStatus);
        String payload = jsonCodec.toJson(Map.of(
            PAYLOAD_TRANSITION, Map.of(PAYLOAD_ID, transitionId)
        ));

        HttpRequest request = baseRequest(API_TRANSITIONS_PATH_TEMPLATE.formatted(command.workItemKey()))
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        send(request, "Jira transition failed");
        LOG.infof("Transitioned Jira ticket %s to status %s", command.workItemKey(), targetStatus);
    }

    @Override
    public Optional<WorkItem> loadWorkItem(String workItemSystem, String workItemKey) {
        if (!JiraSystem.matches(workItemSystem)) {
            return Optional.empty();
        }

        HttpRequest request = baseRequest(API_ISSUE_PATH_TEMPLATE.formatted(workItemKey) + QUERY_WORK_ITEM_FIELDS)
            .GET()
            .build();
        return Optional.of(jiraPayloadMapper.toWorkItem(sendJson(request, "Unable to read Jira issue")));
    }

    @Override
    public List<IncomingComment> listComments(String workItemSystem, String workItemKey) {
        if (!JiraSystem.matches(workItemSystem)) {
            return List.of();
        }

        List<IncomingComment> comments = new ArrayList<>();
        int startAt = 0;
        int pageSize = 100;
        boolean lastPage = false;
        while (!lastPage) {
            HttpRequest request = baseRequest(API_COMMENT_PATH_TEMPLATE.formatted(workItemKey) + QUERY_COMMENT_PAGE_TEMPLATE.formatted(startAt, pageSize))
                .GET()
                .build();
            Map<String, Object> payload = sendJson(request, "Unable to read Jira comments");
            List<IncomingComment> page = jiraPayloadMapper.extractComments(payload, workItemKey);
            comments.addAll(page);

            int total = intValue(payload.get(PAYLOAD_TOTAL), comments.size());
            int responseStartAt = intValue(payload.get(PAYLOAD_START_AT), startAt);
            int responsePageSize = Math.max(1, intValue(payload.get(PAYLOAD_MAX_RESULTS), pageSize));
            startAt = responseStartAt + responsePageSize;
            lastPage = page.isEmpty() || comments.size() >= total;
        }
        return comments;
    }

    private String currentStatus(String workItemKey) {
        HttpRequest request = baseRequest(API_ISSUE_PATH_TEMPLATE.formatted(workItemKey) + QUERY_FIELDS_STATUS)
            .GET()
            .build();

        Map<String, Object> payload = sendJson(request, "Unable to read Jira issue");
        Map<String, Object> fields = map(payload.get(PAYLOAD_FIELDS));
        return string(map(fields.get(PAYLOAD_STATUS)).get(PAYLOAD_NAME));
    }

    private String resolveTransitionId(String workItemKey, String targetStatus) {
        HttpRequest request = baseRequest(API_TRANSITIONS_PATH_TEMPLATE.formatted(workItemKey))
            .GET()
            .build();

        Map<String, Object> payload = sendJson(request, "Unable to read Jira transitions");
        List<Map<String, Object>> transitions = listOfMap(payload.get(PAYLOAD_TRANSITIONS));
        for (Map<String, Object> transition : transitions) {
            String candidateStatus = string(map(transition.get(PAYLOAD_TO)).get(PAYLOAD_NAME));
            if (matchesStatus(candidateStatus, targetStatus)) {
                return string(transition.get(PAYLOAD_ID));
            }
        }

        throw new DomainException(
            "No Jira transition to status '" + targetStatus + "' is available for ticket " + workItemKey
                + ". Available targets: " + availableTransitionTargets(transitions)
        );
    }

    private String availableTransitionTargets(List<Map<String, Object>> transitions) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Map<String, Object> transition : transitions) {
            String candidateStatus = string(map(transition.get(PAYLOAD_TO)).get(PAYLOAD_NAME));
            if (candidateStatus != null && !candidateStatus.isBlank()) {
                joiner.add(candidateStatus);
            }
        }
        return joiner.toString();
    }

    private String resolveTargetStatus(WorkItemTransitionTarget target) {
        return switch (target) {
            case IN_PROGRESS -> config.inProgressStatus();
            case BLOCKED -> config.blockedStatus();
            case TO_REVIEW -> config.reviewStatus();
            case TO_VALIDATE -> config.validateStatus();
            case DONE -> config.doneStatus();
        };
    }

    private boolean matchesStatus(String currentStatus, String targetStatus) {
        return currentStatus != null && currentStatus.equalsIgnoreCase(targetStatus);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number current) {
            return current.intValue();
        }
        if (value instanceof String current) {
            try {
                return Integer.parseInt(current);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + path))
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BASIC + encodedCredentials())
            .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            .timeout(HTTP_TIMEOUT);
    }

    private Map<String, Object> sendJson(HttpRequest request, String failureMessage) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new DomainException(failureMessage + ": HTTP " + response.statusCode() + " - " + response.body());
            }
            return jsonCodec.toMap(response.body());
        } catch (IOException exception) {
            throw new DomainException(failureMessage, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DomainException(failureMessage, exception);
        }
    }

    private void send(HttpRequest request, String failureMessage) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new DomainException(failureMessage + ": HTTP " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException exception) {
            throw new DomainException(failureMessage, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DomainException(failureMessage, exception);
        }
    }

    private String encodedCredentials() {
        String credentials = config.userEmail() + ":" + config.apiToken();
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> buildCommentPayload(String comment) {
        return Map.of(
            ADF_BODY, Map.of(
                ADF_TYPE, ADF_DOC,
                ADF_VERSION, ADF_VERSION_VALUE,
                ADF_CONTENT, MarkdownToAdfConverter.convert(comment)
            )
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> current ? (Map<String, Object>) current : Map.of();
    }

    private List<Map<String, Object>> listOfMap(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(current -> {
                Map<String, Object> normalized = new LinkedHashMap<>();
                ((Map<?, ?>) current).forEach((key, element) -> normalized.put(String.valueOf(key), element));
                return normalized;
            })
            .toList();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

}
