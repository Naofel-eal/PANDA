package io.devflow.domain.workflow;

import io.devflow.domain.ticketing.WorkItemRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class Workflow {

    private final UUID id;
    private final String tenantId;
    private final String workflowDefinitionId;
    private final String workItemSystem;
    private final String workItemKey;
    private final WorkflowPhase phase;
    private final WorkflowStatus status;
    private final UUID currentBlockerId;
    private final String contextJson;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Workflow(
        UUID id,
        String tenantId,
        String workflowDefinitionId,
        String workItemSystem,
        String workItemKey,
        WorkflowPhase phase,
        WorkflowStatus status,
        UUID currentBlockerId,
        String contextJson,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.workflowDefinitionId = Objects.requireNonNull(workflowDefinitionId, "workflowDefinitionId must not be null");
        this.workItemSystem = Objects.requireNonNull(workItemSystem, "workItemSystem must not be null");
        this.workItemKey = Objects.requireNonNull(workItemKey, "workItemKey must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.currentBlockerId = currentBlockerId;
        this.contextJson = contextJson == null ? "{}" : contextJson;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static Workflow create(
        UUID id,
        String tenantId,
        String workflowDefinitionId,
        WorkItemRef workItem,
        Instant now
    ) {
        return new Workflow(
            id,
            tenantId,
            workflowDefinitionId,
            workItem.system(),
            workItem.key(),
            WorkflowPhase.INFORMATION_COLLECTION,
            WorkflowStatus.ACTIVE,
            null,
            "{}",
            0,
            now,
            now
        );
    }

    public static Workflow rehydrate(
        UUID id,
        String tenantId,
        String workflowDefinitionId,
        String workItemSystem,
        String workItemKey,
        WorkflowPhase phase,
        WorkflowStatus status,
        UUID currentBlockerId,
        String contextJson,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new Workflow(
            id,
            tenantId,
            workflowDefinitionId,
            workItemSystem,
            workItemKey,
            phase,
            status,
            currentBlockerId,
            contextJson,
            version,
            createdAt,
            updatedAt
        );
    }

    public WorkItemRef workItem() {
        return new WorkItemRef(workItemSystem, workItemKey, null);
    }

    public Workflow moveTo(WorkflowPhase nextPhase, WorkflowStatus nextStatus, Instant now) {
        return new Workflow(
            id,
            tenantId,
            workflowDefinitionId,
            workItemSystem,
            workItemKey,
            Objects.requireNonNull(nextPhase, "nextPhase must not be null"),
            Objects.requireNonNull(nextStatus, "nextStatus must not be null"),
            currentBlockerId,
            contextJson,
            version,
            createdAt,
            requireInstant(now)
        );
    }

    public Workflow markCancelled(Instant now) {
        return new Workflow(id, tenantId, workflowDefinitionId, workItemSystem, workItemKey, phase, WorkflowStatus.CANCELLED, currentBlockerId, contextJson, version, createdAt, requireInstant(now));
    }

    public Workflow markCompleted(Instant now) {
        return new Workflow(id, tenantId, workflowDefinitionId, workItemSystem, workItemKey, WorkflowPhase.DONE, WorkflowStatus.COMPLETED, currentBlockerId, contextJson, version, createdAt, requireInstant(now));
    }

    public Workflow markFailed(Instant now) {
        return new Workflow(id, tenantId, workflowDefinitionId, workItemSystem, workItemKey, phase, WorkflowStatus.FAILED, currentBlockerId, contextJson, version, createdAt, requireInstant(now));
    }

    public Workflow markActive(Instant now) {
        return new Workflow(id, tenantId, workflowDefinitionId, workItemSystem, workItemKey, phase, WorkflowStatus.ACTIVE, currentBlockerId, contextJson, version, createdAt, requireInstant(now));
    }

    public Workflow replaceContext(String nextContextJson, Instant now) {
        return new Workflow(id, tenantId, workflowDefinitionId, workItemSystem, workItemKey, phase, status, currentBlockerId, nextContextJson == null ? "{}" : nextContextJson, version, createdAt, requireInstant(now));
    }

    public Workflow attachBlocker(UUID blockerId, WorkflowStatus nextStatus, Instant now) {
        return new Workflow(
            id,
            tenantId,
            workflowDefinitionId,
            workItemSystem,
            workItemKey,
            phase,
            Objects.requireNonNull(nextStatus, "nextStatus must not be null"),
            blockerId,
            contextJson,
            version,
            createdAt,
            requireInstant(now)
        );
    }

    public Workflow clearBlocker(Instant now) {
        return new Workflow(id, tenantId, workflowDefinitionId, workItemSystem, workItemKey, phase, status, null, contextJson, version, createdAt, requireInstant(now));
    }

    public Workflow touch(Instant now) {
        return new Workflow(id, tenantId, workflowDefinitionId, workItemSystem, workItemKey, phase, status, currentBlockerId, contextJson, version, createdAt, requireInstant(now));
    }

    private Instant requireInstant(Instant now) {
        return Objects.requireNonNull(now, "now must not be null");
    }
}
