package io.devflow.domain.messaging;

import io.devflow.domain.agent.AgentCommandType;
import io.devflow.domain.messaging.OutboxCommandStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class OutboxCommand {

    private final UUID id;
    private final UUID workflowId;
    private final UUID agentRunId;
    private final AgentCommandType commandType;
    private final String payloadJson;
    private final Instant createdAt;
    private final Instant processedAt;
    private final Instant updatedAt;
    private final OutboxCommandStatus status;
    private final String failureReason;

    private OutboxCommand(
        UUID id,
        UUID workflowId,
        UUID agentRunId,
        AgentCommandType commandType,
        String payloadJson,
        Instant createdAt,
        Instant processedAt,
        Instant updatedAt,
        OutboxCommandStatus status,
        String failureReason
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId must not be null");
        this.agentRunId = agentRunId;
        this.commandType = Objects.requireNonNull(commandType, "commandType must not be null");
        this.payloadJson = Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.processedAt = processedAt;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.failureReason = failureReason;
    }

    public static OutboxCommand enqueue(
        UUID id,
        UUID workflowId,
        UUID agentRunId,
        AgentCommandType commandType,
        String payloadJson,
        Instant now
    ) {
        return new OutboxCommand(
            id,
            workflowId,
            agentRunId,
            commandType,
            payloadJson,
            now,
            null,
            now,
            OutboxCommandStatus.PENDING,
            null
        );
    }

    public static OutboxCommand rehydrate(
        UUID id,
        UUID workflowId,
        UUID agentRunId,
        AgentCommandType commandType,
        String payloadJson,
        Instant createdAt,
        Instant processedAt,
        Instant updatedAt,
        OutboxCommandStatus status,
        String failureReason
    ) {
        return new OutboxCommand(id, workflowId, agentRunId, commandType, payloadJson, createdAt, processedAt, updatedAt, status, failureReason);
    }

    public OutboxCommand markProcessing(Instant now) {
        Instant effectiveTime = Objects.requireNonNull(now, "now must not be null");
        return new OutboxCommand(id, workflowId, agentRunId, commandType, payloadJson, createdAt, processedAt, effectiveTime, OutboxCommandStatus.PROCESSING, failureReason);
    }

    public OutboxCommand markProcessed(Instant now) {
        Instant effectiveTime = Objects.requireNonNull(now, "now must not be null");
        return new OutboxCommand(id, workflowId, agentRunId, commandType, payloadJson, createdAt, effectiveTime, effectiveTime, OutboxCommandStatus.PROCESSED, failureReason);
    }

    public OutboxCommand markFailed(String failureReason, Instant now) {
        Instant effectiveTime = Objects.requireNonNull(now, "now must not be null");
        return new OutboxCommand(id, workflowId, agentRunId, commandType, payloadJson, createdAt, processedAt, effectiveTime, OutboxCommandStatus.PENDING, failureReason);
    }
}
