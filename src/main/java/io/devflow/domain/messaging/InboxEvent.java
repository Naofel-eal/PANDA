package io.devflow.domain.messaging;

import io.devflow.domain.messaging.EventProcessingStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class InboxEvent {

    private final UUID id;
    private final String sourceSystem;
    private final String sourceEventType;
    private final String sourceEventId;
    private final String payloadHash;
    private final Instant receivedAt;
    private final Instant processedAt;
    private final EventProcessingStatus status;

    private InboxEvent(
        UUID id,
        String sourceSystem,
        String sourceEventType,
        String sourceEventId,
        String payloadHash,
        Instant receivedAt,
        Instant processedAt,
        EventProcessingStatus status
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sourceSystem = Objects.requireNonNull(sourceSystem, "sourceSystem must not be null");
        this.sourceEventType = Objects.requireNonNull(sourceEventType, "sourceEventType must not be null");
        this.sourceEventId = sourceEventId;
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash must not be null");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        this.processedAt = processedAt;
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public static InboxEvent receive(
        UUID id,
        String sourceSystem,
        String sourceEventType,
        String sourceEventId,
        String payloadHash,
        Instant receivedAt
    ) {
        return new InboxEvent(id, sourceSystem, sourceEventType, sourceEventId, payloadHash, receivedAt, null, EventProcessingStatus.NEW);
    }

    public static InboxEvent rehydrate(
        UUID id,
        String sourceSystem,
        String sourceEventType,
        String sourceEventId,
        String payloadHash,
        Instant receivedAt,
        Instant processedAt,
        EventProcessingStatus status
    ) {
        return new InboxEvent(id, sourceSystem, sourceEventType, sourceEventId, payloadHash, receivedAt, processedAt, status);
    }

    public InboxEvent markProcessed(Instant now) {
        return new InboxEvent(id, sourceSystem, sourceEventType, sourceEventId, payloadHash, receivedAt, now, EventProcessingStatus.PROCESSED);
    }

    public InboxEvent markFailed(Instant now) {
        return new InboxEvent(id, sourceSystem, sourceEventType, sourceEventId, payloadHash, receivedAt, now, EventProcessingStatus.FAILED);
    }
}
