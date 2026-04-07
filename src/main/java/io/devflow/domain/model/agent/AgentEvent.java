package io.devflow.domain.model.agent;

import io.devflow.domain.model.codehost.CodeChangeRef;
import io.devflow.domain.model.workflow.BlockerType;
import io.devflow.domain.model.workflow.RequestedFrom;
import io.devflow.domain.model.workflow.ResumeTrigger;
import io.devflow.domain.model.workflow.WorkflowPhase;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class AgentEvent {

    private final String eventId;
    private final UUID workflowId;
    private final UUID agentRunId;
    private final AgentEventType type;
    private final Instant occurredAt;
    private final String providerRunRef;
    private final String summary;
    private final BlockerType blockerType;
    private final String reasonCode;
    private final RequestedFrom requestedFrom;
    private final ResumeTrigger resumeTrigger;
    private final String suggestedComment;
    private final Map<String, Object> artifacts;
    private final Map<String, Object> details;

    public AgentEvent(
        String eventId,
        UUID workflowId,
        UUID agentRunId,
        AgentEventType type,
        Instant occurredAt,
        String providerRunRef,
        String summary,
        BlockerType blockerType,
        String reasonCode,
        RequestedFrom requestedFrom,
        ResumeTrigger resumeTrigger,
        String suggestedComment,
        Map<String, Object> artifacts,
        Map<String, Object> details
    ) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        this.type = Objects.requireNonNull(type, "type");
        this.occurredAt = occurredAt;
        this.providerRunRef = providerRunRef;
        this.summary = summary;
        this.blockerType = blockerType;
        this.reasonCode = reasonCode;
        this.requestedFrom = requestedFrom;
        this.resumeTrigger = resumeTrigger;
        this.suggestedComment = suggestedComment;
        this.artifacts = copyNonNull(artifacts);
        this.details = copyNonNull(details);
    }

    private static Map<String, Object> copyNonNull(Map<String, Object> map) {
        if (map == null) return Map.of();
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> { if (k != null) copy.put(k, v); });
        return Collections.unmodifiableMap(copy);
    }

    // --- Normalization for INPUT_REQUIRED ---

    public BlockerType resolvedBlockerType() {
        return Objects.requireNonNullElse(blockerType, BlockerType.MISSING_TICKET_INFORMATION);
    }

    public String normalizedReasonCode(WorkflowPhase phase) {
        if (type == AgentEventType.FAILED) {
            return normalizeFailureReasonCode();
        }
        String normalized = normalizeText(reasonCode);
        if (normalized == null) {
            return defaultReasonCode(resolvedBlockerType());
        }
        String machineReadable = normalized
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return machineReadable.isBlank() ? defaultReasonCode(resolvedBlockerType()) : machineReadable;
    }

    public RequestedFrom resolvedRequestedFrom(WorkflowPhase phase) {
        if (requestedFrom != null) {
            return requestedFrom;
        }
        return defaultRequestedFrom(resolvedBlockerType(), phase);
    }

    public ResumeTrigger resolvedResumeTrigger(WorkflowPhase phase) {
        if (resumeTrigger != null) {
            return resumeTrigger;
        }
        RequestedFrom resolved = resolvedRequestedFrom(phase);
        return defaultResumeTrigger(resolvedBlockerType(), phase, resolved);
    }

    public String normalizedSuggestedComment() {
        String normalized = normalizeText(suggestedComment);
        if (normalized != null) {
            return normalized;
        }
        List<String> missing = extractMissingInformation();
        if (!missing.isEmpty()) {
            return truncate("Clarification needed before continuing: " + missing.getFirst(), 320);
        }
        return "Clarification is needed before the agent can continue this ticket.";
    }

    public String normalizedSummary() {
        String normalized = normalizeText(summary);
        if (normalized != null) {
            return normalized;
        }
        List<String> missing = extractMissingInformation();
        if (!missing.isEmpty()) {
            return truncate("Missing information: " + missing.getFirst(), 280);
        }
        return "Additional clarification is required before the agent can continue.";
    }

    // --- Failure comment building ---

    public String buildFailureComment() {
        String normalizedSummary = normalizeText(summary);
        String detail = extractFailureDetail();
        StringBuilder comment = new StringBuilder("Implementation failed.");
        if (normalizedSummary != null) {
            comment.append("\n\n").append(normalizedSummary);
        }
        if (detail != null && !detail.equals(normalizedSummary)) {
            comment.append("\n\nDetail: ").append(detail);
        }
        comment.append("\n\nDevflow marked this ticket as blocked until the issue is addressed or the run is resumed.");
        return comment.toString();
    }

    // --- Technical validation comment ---

    public String buildTechnicalValidationComment(List<CodeChangeRef> codeChanges) {
        StringBuilder comment = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            comment.append(summary.trim());
        } else {
            comment.append("Implementation completed and ready for technical validation.");
        }
        if (!codeChanges.isEmpty()) {
            comment.append("\n\n");
            comment.append(codeChanges.size() == 1 ? "Pull request created:" : "Pull requests created:");
            for (CodeChangeRef codeChange : codeChanges) {
                comment.append("\n- ").append(codeChange.repository());
                if (codeChange.url() != null && !codeChange.url().isBlank()) {
                    comment.append(": ").append(codeChange.url());
                }
            }
        }
        return comment.toString();
    }

    // --- Repository extraction from artifacts ---

    @SuppressWarnings("unchecked")
    public List<String> extractRepoChangesRepositories() {
        Object repoChanges = artifacts.get("repoChanges");
        if (repoChanges instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                .filter(Map.class::isInstance)
                .map(entry -> ((Map<String, Object>) entry).get("repository"))
                .filter(repo -> repo instanceof String s && !s.isBlank())
                .map(String::valueOf)
                .toList();
        }
        return List.of();
    }

    // --- Private helpers ---

    private List<String> extractMissingInformation() {
        Map<String, Object> sanitized = sanitizeDetails();
        Object missingInformation = sanitized.get("missingInformation");
        if (missingInformation instanceof List<?> values) {
            return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        }
        return List.of();
    }

    private Map<String, Object> sanitizeDetails() {
        if (details.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>(details);
        Object missingInformation = sanitized.get("missingInformation");
        if (missingInformation instanceof List<?> values) {
            List<String> cleaned = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(AgentEvent::normalizeText)
                .filter(Objects::nonNull)
                .limit(2)
                .toList();
            if (cleaned.isEmpty()) {
                sanitized.remove("missingInformation");
            } else {
                sanitized.put("missingInformation", cleaned);
            }
        }
        return sanitized;
    }

    private String normalizeFailureReasonCode() {
        String normalized = normalizeText(reasonCode);
        if (normalized == null) {
            return "AGENT_RUN_FAILED";
        }
        String machineReadable = normalized
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return machineReadable.isBlank() ? "AGENT_RUN_FAILED" : machineReadable;
    }

    private String extractFailureDetail() {
        if (details.isEmpty()) {
            return null;
        }
        Object error = details.get("error");
        if (error instanceof String text) {
            return normalizeText(text);
        }
        Object solution = details.get("solution");
        if (solution instanceof String text) {
            return normalizeText(text);
        }
        return null;
    }

    private static RequestedFrom defaultRequestedFrom(BlockerType blockerType, WorkflowPhase phase) {
        return switch (blockerType) {
            case MISSING_REPOSITORY_MAPPING, NO_EXECUTION_ENVIRONMENT -> RequestedFrom.SYSTEM;
            case MISSING_TECHNICAL_FEEDBACK_CONTEXT, WAITING_TECHNICAL_REVIEW -> RequestedFrom.DEV;
            case WAITING_BUSINESS_FEEDBACK -> RequestedFrom.BUSINESS;
            default -> phase == WorkflowPhase.TECHNICAL_VALIDATION ? RequestedFrom.DEV : RequestedFrom.BUSINESS;
        };
    }

    private static ResumeTrigger defaultResumeTrigger(BlockerType blockerType, WorkflowPhase phase, RequestedFrom requestedFrom) {
        if (requestedFrom == RequestedFrom.SYSTEM) {
            return ResumeTrigger.MANUAL_RESUME_REQUESTED;
        }
        if (phase == WorkflowPhase.TECHNICAL_VALIDATION
            || blockerType == BlockerType.MISSING_TECHNICAL_FEEDBACK_CONTEXT
            || blockerType == BlockerType.WAITING_TECHNICAL_REVIEW) {
            return ResumeTrigger.CODE_CHANGE_REVIEW_COMMENT_RECEIVED;
        }
        if (phase == WorkflowPhase.BUSINESS_VALIDATION || blockerType == BlockerType.WAITING_BUSINESS_FEEDBACK) {
            return ResumeTrigger.BUSINESS_VALIDATION_REPORTED;
        }
        return ResumeTrigger.WORK_ITEM_COMMENT_RECEIVED;
    }

    private static String defaultReasonCode(BlockerType blockerType) {
        return switch (blockerType) {
            case MISSING_REPOSITORY_MAPPING -> "NO_REPOSITORIES_FOUND";
            case NO_EXECUTION_ENVIRONMENT -> "NO_EXECUTION_ENVIRONMENT";
            case MISSING_TECHNICAL_FEEDBACK_CONTEXT -> "MISSING_TECHNICAL_FEEDBACK_CONTEXT";
            case WAITING_TECHNICAL_REVIEW -> "WAITING_TECHNICAL_REVIEW";
            case WAITING_BUSINESS_FEEDBACK -> "WAITING_BUSINESS_FEEDBACK";
            case NOT_ELIGIBLE -> "NOT_ELIGIBLE";
            case MISSING_TICKET_INFORMATION -> "TASK_CONTEXT_UNCLEAR";
        };
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3).trim() + "...";
    }
}
