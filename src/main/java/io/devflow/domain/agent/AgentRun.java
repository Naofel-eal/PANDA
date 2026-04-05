package io.devflow.domain.agent;

import io.devflow.domain.agent.AgentRunStatus;
import io.devflow.domain.workflow.WorkflowPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class AgentRun {

    private final UUID id;
    private final UUID workflowId;
    private final WorkflowPhase phase;
    private final AgentRunStatus status;
    private final String inputSnapshotJson;
    private final String providerRunRef;
    private final Instant startedAt;
    private final Instant endedAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    private AgentRun(
        UUID id,
        UUID workflowId,
        WorkflowPhase phase,
        AgentRunStatus status,
        String inputSnapshotJson,
        String providerRunRef,
        Instant startedAt,
        Instant endedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.inputSnapshotJson = inputSnapshotJson == null ? "{}" : inputSnapshotJson;
        this.providerRunRef = providerRunRef;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static AgentRun schedule(UUID id, UUID workflowId, WorkflowPhase phase, String inputSnapshotJson, Instant now) {
        return new AgentRun(id, workflowId, phase, AgentRunStatus.PENDING, inputSnapshotJson, null, null, null, now, now);
    }

    public static AgentRun rehydrate(
        UUID id,
        UUID workflowId,
        WorkflowPhase phase,
        AgentRunStatus status,
        String inputSnapshotJson,
        String providerRunRef,
        Instant startedAt,
        Instant endedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new AgentRun(id, workflowId, phase, status, inputSnapshotJson, providerRunRef, startedAt, endedAt, createdAt, updatedAt);
    }

    public AgentRun markStarting(Instant now) {
        return new AgentRun(id, workflowId, phase, AgentRunStatus.STARTING, inputSnapshotJson, providerRunRef, startedAt, endedAt, createdAt, requireInstant(now));
    }

    public AgentRun markRunning(Instant occurredAt, String providerRunRef) {
        Instant effectiveTime = occurredAt == null ? Instant.now() : occurredAt;
        String nextProviderRunRef = providerRunRef != null && !providerRunRef.isBlank() ? providerRunRef : this.providerRunRef;
        return new AgentRun(
            id,
            workflowId,
            phase,
            AgentRunStatus.RUNNING,
            inputSnapshotJson,
            nextProviderRunRef,
            effectiveTime,
            endedAt,
            createdAt,
            effectiveTime
        );
    }

    public AgentRun waitForInput(Instant now) {
        Instant effectiveTime = requireInstant(now);
        return new AgentRun(id, workflowId, phase, AgentRunStatus.WAITING_INPUT, inputSnapshotJson, providerRunRef, startedAt, effectiveTime, createdAt, effectiveTime);
    }

    public AgentRun markCompleted(Instant now) {
        Instant effectiveTime = requireInstant(now);
        return new AgentRun(id, workflowId, phase, AgentRunStatus.COMPLETED, inputSnapshotJson, providerRunRef, startedAt, effectiveTime, createdAt, effectiveTime);
    }

    public AgentRun markFailed(Instant now) {
        Instant effectiveTime = requireInstant(now);
        return new AgentRun(id, workflowId, phase, AgentRunStatus.FAILED, inputSnapshotJson, providerRunRef, startedAt, effectiveTime, createdAt, effectiveTime);
    }

    public AgentRun markCancelled(Instant now) {
        Instant effectiveTime = requireInstant(now);
        return new AgentRun(id, workflowId, phase, AgentRunStatus.CANCELLED, inputSnapshotJson, providerRunRef, startedAt, effectiveTime, createdAt, effectiveTime);
    }

    public AgentRun resetToPending(Instant now) {
        Instant effectiveTime = requireInstant(now);
        return new AgentRun(id, workflowId, phase, AgentRunStatus.PENDING, inputSnapshotJson, providerRunRef, startedAt, endedAt, createdAt, effectiveTime);
    }

    private Instant requireInstant(Instant now) {
        return Objects.requireNonNull(now, "now must not be null");
    }
}
