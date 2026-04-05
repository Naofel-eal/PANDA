package io.devflow.domain.workflow;

import io.devflow.domain.workflow.WorkflowAuditType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class WorkflowEvent {

    private final UUID id;
    private final UUID workflowId;
    private final WorkflowAuditType eventType;
    private final String eventPayloadJson;
    private final Instant occurredAt;
    private final String sourceSystem;
    private final String sourceEventId;

    private WorkflowEvent(
        UUID id,
        UUID workflowId,
        WorkflowAuditType eventType,
        String eventPayloadJson,
        Instant occurredAt,
        String sourceSystem,
        String sourceEventId
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.eventPayloadJson = eventPayloadJson;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.sourceSystem = sourceSystem;
        this.sourceEventId = sourceEventId;
    }

    public static WorkflowEvent record(
        UUID id,
        UUID workflowId,
        WorkflowAuditType eventType,
        String eventPayloadJson,
        Instant occurredAt,
        String sourceSystem,
        String sourceEventId
    ) {
        return new WorkflowEvent(id, workflowId, eventType, eventPayloadJson, occurredAt, sourceSystem, sourceEventId);
    }

    public static WorkflowEvent rehydrate(
        UUID id,
        UUID workflowId,
        WorkflowAuditType eventType,
        String eventPayloadJson,
        Instant occurredAt,
        String sourceSystem,
        String sourceEventId
    ) {
        return new WorkflowEvent(id, workflowId, eventType, eventPayloadJson, occurredAt, sourceSystem, sourceEventId);
    }

}
