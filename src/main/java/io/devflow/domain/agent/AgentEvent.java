package io.devflow.domain.agent;

import io.devflow.domain.agent.AgentEventType;
import io.devflow.domain.messaging.EventProcessingStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class AgentEvent {

    private final UUID id;
    private final UUID agentRunId;
    private final String eventId;
    private final AgentEventType eventType;
    private final String eventPayloadJson;
    private final Instant occurredAt;
    private final Instant processedAt;
    private final EventProcessingStatus status;

    private AgentEvent(
        UUID id,
        UUID agentRunId,
        String eventId,
        AgentEventType eventType,
        String eventPayloadJson,
        Instant occurredAt,
        Instant processedAt,
        EventProcessingStatus status
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.eventPayloadJson = eventPayloadJson;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.processedAt = processedAt;
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public static AgentEvent receive(
        UUID id,
        UUID agentRunId,
        String eventId,
        AgentEventType eventType,
        String eventPayloadJson,
        Instant occurredAt
    ) {
        return new AgentEvent(id, agentRunId, eventId, eventType, eventPayloadJson, occurredAt, null, EventProcessingStatus.NEW);
    }

    public static AgentEvent rehydrate(
        UUID id,
        UUID agentRunId,
        String eventId,
        AgentEventType eventType,
        String eventPayloadJson,
        Instant occurredAt,
        Instant processedAt,
        EventProcessingStatus status
    ) {
        return new AgentEvent(id, agentRunId, eventId, eventType, eventPayloadJson, occurredAt, processedAt, status);
    }

    public AgentEvent markProcessed(Instant now) {
        return new AgentEvent(id, agentRunId, eventId, eventType, eventPayloadJson, occurredAt, now, EventProcessingStatus.PROCESSED);
    }
}
