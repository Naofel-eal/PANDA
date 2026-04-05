package io.devflow.infrastructure.persistence.postgres.entity;

import io.devflow.domain.messaging.EventProcessingStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbox_event")
public class InboxEventEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "source_system", nullable = false)
    public String sourceSystem;

    @Column(name = "source_event_type", nullable = false)
    public String sourceEventType;

    @Column(name = "source_event_id")
    public String sourceEventId;

    @Column(name = "payload_hash")
    public String payloadHash;

    @Column(name = "received_at", nullable = false)
    public Instant receivedAt;

    @Column(name = "processed_at")
    public Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EventProcessingStatus status;
}
