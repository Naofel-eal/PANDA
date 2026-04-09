package io.devflow.infrastructure.ticketing.jira;

import io.devflow.domain.model.ticketing.ExternalCommentParentType;
import io.devflow.domain.model.ticketing.IncomingComment;
import io.devflow.domain.model.ticketing.WorkItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@ApplicationScoped
public class JiraPayloadMapper {

    @Inject
    JiraConfig jiraConfig;

    private static final DateTimeFormatter JIRA_OFFSET_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .optionalStart()
        .appendPattern(".SSS")
        .optionalEnd()
        .appendOffset("+HHMM", "+0000")
        .toFormatter();

    private static final String PAYLOAD_FIELDS = "fields";
    private static final String PAYLOAD_ISSUE_KEY = "key";
    private static final String PAYLOAD_ID = "id";
    private static final String PAYLOAD_AUTHOR = "author";
    private static final String PAYLOAD_ACCOUNT_ID = "accountId";
    private static final String PAYLOAD_BODY = "body";
    private static final String PAYLOAD_CREATED = "created";
    private static final String PAYLOAD_UPDATED = "updated";
    private static final String PAYLOAD_TEXT = "text";
    private static final String PAYLOAD_CONTENT = "content";
    private static final String PAYLOAD_NAME = "name";
    private static final String PAYLOAD_ISSUES = "issues";
    private static final String PAYLOAD_COMMENTS = "comments";
    private static final String ISSUE_FIELD_TYPE = "issuetype";
    private static final String ISSUE_FIELD_STATUS = "status";
    private static final String ISSUE_FIELD_SUMMARY = "summary";
    private static final String ISSUE_FIELD_DESCRIPTION = "description";
    private static final String ISSUE_FIELD_LABELS = "labels";
    private static final String PAYLOAD_NEXT_PAGE_TOKEN = "nextPageToken";
    private static final String PAYLOAD_IS_LAST = "isLast";

    public WorkItem toWorkItem(Map<String, Object> issue) {
        Map<String, Object> fields = map(issue.get(PAYLOAD_FIELDS));
        String key = string(issue.get(PAYLOAD_ISSUE_KEY));
        return new WorkItem(
            key,
            extractName(fields.get(ISSUE_FIELD_TYPE)),
            extractText(fields.get(ISSUE_FIELD_SUMMARY)),
            extractText(fields.get(ISSUE_FIELD_DESCRIPTION)),
            extractName(fields.get(ISSUE_FIELD_STATUS)),
            buildBrowseUrl(key),
            extractNames(fields.get(ISSUE_FIELD_LABELS)),
            List.of(),
            extractIssueUpdatedAt(issue)
        );
    }

    private String buildBrowseUrl(String issueKey) {
        if (issueKey == null) {
            return null;
        }
        String base = jiraConfig.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/browse/" + issueKey;
    }

    public IncomingComment toComment(Map<String, Object> comment, String issueKey) {
        Map<String, Object> author = map(comment.get(PAYLOAD_AUTHOR));
        return new IncomingComment(
            string(comment.get(PAYLOAD_ID)),
            ExternalCommentParentType.WORK_ITEM.id(),
            issueKey,
            string(author.get(PAYLOAD_ACCOUNT_ID)),
            extractText(comment.get(PAYLOAD_BODY)),
            extractInstant(comment.get(PAYLOAD_CREATED)),
            extractInstant(comment.get(PAYLOAD_UPDATED))
        );
    }

    public List<Map<String, Object>> extractIssues(Map<String, Object> payload) {
        Object rawIssues = payload.get(PAYLOAD_ISSUES);
        if (rawIssues instanceof List<?> issues) {
            return issues.stream()
                .filter(Map.class::isInstance)
                .map(current -> (Map<String, Object>) current)
                .toList();
        }
        return List.of();
    }

    public List<IncomingComment> extractComments(Map<String, Object> payload, String issueKey) {
        Object rawComments = payload.get(PAYLOAD_COMMENTS);
        if (rawComments instanceof List<?> comments) {
            return comments.stream()
                .filter(Map.class::isInstance)
                .map(current -> toComment((Map<String, Object>) current, issueKey))
                .toList();
        }
        return List.of();
    }

    public Instant extractIssueUpdatedAt(Map<String, Object> issue) {
        return extractInstant(map(issue.get(PAYLOAD_FIELDS)).get(PAYLOAD_UPDATED));
    }

    public String extractNextPageToken(Map<String, Object> payload) {
        return string(payload.get(PAYLOAD_NEXT_PAGE_TOKEN));
    }

    public boolean isLastPage(Map<String, Object> payload) {
        Object value = payload.get(PAYLOAD_IS_LAST);
        return value instanceof Boolean current && current;
    }

    private List<String> extractNames(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String extractName(Object value) {
        return value instanceof Map<?, ?> map ? string(map.get(PAYLOAD_NAME)) : string(value);
    }

    private String extractText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String current) {
            return current;
        }
        StringJoiner joiner = new StringJoiner("\n");
        appendText(joiner, value);
        String text = joiner.toString().trim();
        return text.isBlank() ? null : text;
    }

    private void appendText(StringJoiner joiner, Object value) {
        if (value instanceof String current) {
            if (!current.isBlank()) {
                joiner.add(current);
            }
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(current -> appendText(joiner, current));
            return;
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = rawMap.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()),
                    Map.Entry::getValue,
                    (left, right) -> right,
                    java.util.LinkedHashMap::new
                )
            );
            Object text = map.get(PAYLOAD_TEXT);
            if (text instanceof String current && !current.isBlank()) {
                joiner.add(current);
            }
            appendText(joiner, map.get(PAYLOAD_CONTENT));
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Instant extractInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number current) {
            return Instant.ofEpochMilli(current.longValue());
        }
        String rawValue = String.valueOf(value);
        try {
            return Instant.parse(rawValue);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(rawValue, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeParseException secondIgnored) {
                return OffsetDateTime.parse(rawValue, JIRA_OFFSET_DATE_TIME_FORMATTER).toInstant();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> current ? (Map<String, Object>) current : Map.of();
    }
}
