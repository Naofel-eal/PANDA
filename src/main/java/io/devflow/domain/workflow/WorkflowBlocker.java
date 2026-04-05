package io.devflow.domain.workflow;

import io.devflow.domain.workflow.BlockerStatus;
import io.devflow.domain.workflow.BlockerType;
import io.devflow.domain.workflow.RequestedFrom;
import io.devflow.domain.workflow.ResumeTrigger;
import io.devflow.domain.workflow.WorkflowPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class WorkflowBlocker {

    private final UUID id;
    private final UUID workflowId;
    private final WorkflowPhase phase;
    private final BlockerType type;
    private final String reasonCode;
    private final String summary;
    private final String suggestedComment;
    private final RequestedFrom requestedFrom;
    private final ResumeTrigger resumeTrigger;
    private final String detailsJson;
    private final Instant openedAt;
    private final Instant resolvedAt;
    private final BlockerStatus status;

    private WorkflowBlocker(
        UUID id,
        UUID workflowId,
        WorkflowPhase phase,
        BlockerType type,
        String reasonCode,
        String summary,
        String suggestedComment,
        RequestedFrom requestedFrom,
        ResumeTrigger resumeTrigger,
        String detailsJson,
        Instant openedAt,
        Instant resolvedAt,
        BlockerStatus status
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.reasonCode = reasonCode;
        this.summary = summary;
        this.suggestedComment = suggestedComment;
        this.requestedFrom = Objects.requireNonNull(requestedFrom, "requestedFrom must not be null");
        this.resumeTrigger = Objects.requireNonNull(resumeTrigger, "resumeTrigger must not be null");
        this.detailsJson = detailsJson;
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        this.resolvedAt = resolvedAt;
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public static WorkflowBlocker open(
        UUID id,
        UUID workflowId,
        WorkflowPhase phase,
        BlockerType type,
        String reasonCode,
        String summary,
        String suggestedComment,
        RequestedFrom requestedFrom,
        ResumeTrigger resumeTrigger,
        String detailsJson,
        Instant now
    ) {
        return new WorkflowBlocker(
            id,
            workflowId,
            phase,
            type,
            reasonCode,
            summary,
            suggestedComment,
            requestedFrom,
            resumeTrigger,
            detailsJson,
            now,
            null,
            BlockerStatus.OPEN
        );
    }

    public static WorkflowBlocker rehydrate(
        UUID id,
        UUID workflowId,
        WorkflowPhase phase,
        BlockerType type,
        String reasonCode,
        String summary,
        String suggestedComment,
        RequestedFrom requestedFrom,
        ResumeTrigger resumeTrigger,
        String detailsJson,
        Instant openedAt,
        Instant resolvedAt,
        BlockerStatus status
    ) {
        return new WorkflowBlocker(
            id,
            workflowId,
            phase,
            type,
            reasonCode,
            summary,
            suggestedComment,
            requestedFrom,
            resumeTrigger,
            detailsJson,
            openedAt,
            resolvedAt,
            status
        );
    }

    public WorkflowBlocker refresh(
        WorkflowPhase phase,
        BlockerType type,
        String reasonCode,
        String summary,
        String suggestedComment,
        RequestedFrom requestedFrom,
        ResumeTrigger resumeTrigger,
        String detailsJson
    ) {
        return new WorkflowBlocker(
            id,
            workflowId,
            Objects.requireNonNull(phase, "phase must not be null"),
            Objects.requireNonNull(type, "type must not be null"),
            reasonCode,
            summary,
            suggestedComment,
            Objects.requireNonNull(requestedFrom, "requestedFrom must not be null"),
            Objects.requireNonNull(resumeTrigger, "resumeTrigger must not be null"),
            detailsJson,
            openedAt,
            null,
            BlockerStatus.OPEN
        );
    }

    public WorkflowBlocker resolve(Instant now) {
        return new WorkflowBlocker(
            id,
            workflowId,
            phase,
            type,
            reasonCode,
            summary,
            suggestedComment,
            requestedFrom,
            resumeTrigger,
            detailsJson,
            openedAt,
            Objects.requireNonNull(now, "now must not be null"),
            BlockerStatus.RESOLVED
        );
    }
}
