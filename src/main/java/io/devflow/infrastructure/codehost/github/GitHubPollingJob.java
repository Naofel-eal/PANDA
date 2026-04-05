package io.devflow.infrastructure.codehost.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.application.command.workflow.WorkflowSignalCommand;
import io.devflow.application.port.persistence.ExternalReferenceStore;
import io.devflow.application.usecase.WorkflowSignalService;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.codehost.CodeChangeStatus;
import io.devflow.domain.codehost.ExternalReference;
import io.devflow.domain.codehost.ExternalReferenceType;
import io.devflow.domain.ticketing.ExternalCommentParentType;
import io.devflow.domain.ticketing.IncomingComment;
import io.devflow.domain.workflow.WorkflowSignalType;
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
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GitHubPollingJob {

    private static final Logger LOG = Logger.getLogger(GitHubPollingJob.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP_TYPE = new TypeReference<>() {
    };
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String GITHUB_ACCEPT_HEADER = "application/vnd.github+json";
    private static final String AUTH_SCHEME_BEARER = "Bearer ";
    private static final String API_PULL_REQUEST_PATH_TEMPLATE = "/repos/%s/pulls/%s";
    private static final String API_PULL_REQUEST_COMMENTS_PATH_TEMPLATE = "/repos/%s/pulls/%s/comments?per_page=100&sort=updated&direction=desc";
    private static final String PAYLOAD_NUMBER = "number";
    private static final String PAYLOAD_STATE = "state";
    private static final String PAYLOAD_MERGED = "merged";
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
    private static final String STATE_OPEN = "open";
    private static final String STATE_CLOSED = "closed";
    private static final String SIGNAL_REVIEW_COMMENT_PREFIX = "github-poll:comment:";
    private static final String SIGNAL_PR_STATE_PREFIX = "github-poll:pr:";

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT)
        .build();

    @Inject
    GitHubConfig config;

    @Inject
    ExternalReferenceStore externalReferenceStore;

    @Inject
    WorkflowSignalService workflowSignalService;

    @Inject
    ObjectMapper objectMapper;

    @Scheduled(
        every = "${devflow.github.poll-interval-minutes:1}m",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    void pollTrackedCodeChanges() {
        if (isBlank(config.token())) {
            return;
        }

        List<ExternalReference> trackedCodeChanges = externalReferenceStore.findByType(ExternalReferenceType.CODE_CHANGE).stream()
            .filter(this::isOpenGitHubCodeChange)
            .toList();
        if (!trackedCodeChanges.isEmpty()) {
            LOG.infof("Polling GitHub state for %d tracked pull requests", trackedCodeChanges.size());
        }
        trackedCodeChanges.forEach(this::pollCodeChange);
    }

    private boolean isOpenGitHubCodeChange(ExternalReference reference) {
        if (!GitHubSystem.ID.equals(reference.system())) {
            return false;
        }
        Object status = metadata(reference).get("status");
        return CodeChangeStatus.OPEN.name().equals(status);
    }

    private void pollCodeChange(ExternalReference reference) {
        Map<String, Object> pullRequest = fetchPullRequest(reference);
        CodeChangeRef codeChange = toCodeChangeRef(reference, pullRequest);
        LOG.infof(
            "Polled GitHub pull request %s for repository %s",
            codeChange.externalId(),
            codeChange.repository()
        );

        if (Boolean.TRUE.equals(pullRequest.get(PAYLOAD_MERGED))) {
            LOG.infof("Detected merged pull request %s", codeChange.externalId());
            dispatch(new WorkflowSignalCommand(
                WorkflowSignalType.CODE_CHANGE_MERGED,
                GitHubSystem.ID,
                buildPullRequestSignalId(reference.externalId(), "merged", extractInstant(pullRequest.get(PAYLOAD_UPDATED_AT))),
                null,
                extractInstant(pullRequest.get(PAYLOAD_UPDATED_AT)),
                null,
                null,
                codeChange,
                null,
                null
            ));
            return;
        }

        if (STATE_CLOSED.equalsIgnoreCase(String.valueOf(pullRequest.get(PAYLOAD_STATE)))) {
            LOG.infof("Detected closed-unmerged pull request %s", codeChange.externalId());
            dispatch(new WorkflowSignalCommand(
                WorkflowSignalType.CODE_CHANGE_CLOSED_UNMERGED,
                GitHubSystem.ID,
                buildPullRequestSignalId(reference.externalId(), "closed", extractInstant(pullRequest.get(PAYLOAD_UPDATED_AT))),
                null,
                extractInstant(pullRequest.get(PAYLOAD_UPDATED_AT)),
                null,
                null,
                codeChange,
                null,
                null
            ));
            return;
        }

        List<Map<String, Object>> reviewComments = fetchReviewComments(codeChange);
        if (!reviewComments.isEmpty()) {
            LOG.infof(
                "Detected %d GitHub review comments on pull request %s",
                reviewComments.size(),
                codeChange.externalId()
            );
        }
        reviewComments.forEach(comment -> dispatch(new WorkflowSignalCommand(
            WorkflowSignalType.CODE_CHANGE_REVIEW_COMMENT_RECEIVED,
            GitHubSystem.ID,
            buildReviewCommentSignalId(comment),
            null,
            extractInstant(comment.get(PAYLOAD_UPDATED_AT)),
            null,
            toIncomingComment(codeChange.externalId(), comment),
            codeChange,
            null,
            null
        )));
    }

    private void dispatch(WorkflowSignalCommand command) {
        workflowSignalService.handle(command);
    }

    private CodeChangeRef toCodeChangeRef(ExternalReference reference, Map<String, Object> pullRequest) {
        Map<String, Object> metadata = metadata(reference);
        String repository = string(metadata.get("repository"));
        String url = string(pullRequest.get(PAYLOAD_HTML_URL));
        String sourceBranch = branchName(pullRequest.get(PAYLOAD_HEAD), metadata.get("sourceBranch"));
        String targetBranch = branchName(pullRequest.get(PAYLOAD_BASE), metadata.get("targetBranch"));
        return new CodeChangeRef(
            GitHubSystem.ID,
            reference.externalId(),
            repository,
            url,
            sourceBranch,
            targetBranch
        );
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

    private List<Map<String, Object>> fetchReviewComments(CodeChangeRef codeChange) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(buildCommentsUri(codeChange.repository(), pullRequestNumber(codeChange.externalId())))
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BEARER + config.token())
            .header(HEADER_ACCEPT, GITHUB_ACCEPT_HEADER)
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GitHub comment polling failed: HTTP " + response.statusCode() + " - " + response.body());
            }
            return objectMapper.readValue(response.body(), LIST_OF_MAP_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to poll GitHub review comments", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling GitHub review comments", exception);
        }
    }

    private Map<String, Object> fetchPullRequest(ExternalReference reference) {
        Map<String, Object> metadata = metadata(reference);
        String repository = string(metadata.get("repository"));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(buildPullRequestUri(repository, pullRequestNumber(reference.externalId())))
            .header(HEADER_AUTHORIZATION, AUTH_SCHEME_BEARER + config.token())
            .header(HEADER_ACCEPT, GITHUB_ACCEPT_HEADER)
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GitHub pull request polling failed: HTTP " + response.statusCode() + " - " + response.body());
            }
            return objectMapper.readValue(response.body(), MAP_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to poll GitHub pull request", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling GitHub pull request", exception);
        }
    }

    private URI buildPullRequestUri(String repository, String pullRequestNumber) {
        return URI.create(config.apiUrl() + API_PULL_REQUEST_PATH_TEMPLATE.formatted(repository, pullRequestNumber));
    }

    private URI buildCommentsUri(String repository, String pullRequestNumber) {
        return URI.create(config.apiUrl() + API_PULL_REQUEST_COMMENTS_PATH_TEMPLATE.formatted(repository, pullRequestNumber));
    }

    private String pullRequestNumber(String externalId) {
        int separator = externalId.lastIndexOf(GitHubCodeHostAdapter.PULL_REQUEST_EXTERNAL_ID_SEPARATOR);
        if (separator < 0 || separator == externalId.length() - 1) {
            throw new IllegalStateException("Invalid GitHub externalId: " + externalId);
        }
        return externalId.substring(separator + 1);
    }

    private String buildReviewCommentSignalId(Map<String, Object> comment) {
        return SIGNAL_REVIEW_COMMENT_PREFIX + string(comment.get(PAYLOAD_ID)) + ":" + String.valueOf(comment.get(PAYLOAD_UPDATED_AT));
    }

    private String buildPullRequestSignalId(String externalId, String state, Instant updatedAt) {
        return SIGNAL_PR_STATE_PREFIX + externalId + ":" + state + ":" + (updatedAt == null ? "unknown" : updatedAt.toString());
    }

    private String branchName(Object branch, Object fallback) {
        String value = string(map(branch).get(PAYLOAD_REF));
        return value == null || value.isBlank() ? string(fallback) : value;
    }

    private Instant extractInstant(Object value) {
        return value == null ? null : Instant.parse(String.valueOf(value));
    }

    private Map<String, Object> metadata(ExternalReference reference) {
        return map(reference.metadataJson() == null ? Map.of() : readJsonMap(reference.metadataJson()));
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse external reference metadata", exception);
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
}
