package io.devflow.infrastructure.persistence.postgres.entity;

import io.devflow.domain.agent.AgentEventType;
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
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "agent_event")
public class AgentEventEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "agent_run_id", nullable = false)
    public UUID agentRunId;

    @Column(name = "event_id", nullable = false)
    public String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    public AgentEventType eventType;

    @Column(name = "event_payload_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    public String eventPayloadJson;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    @Column(name = "processed_at")
    public Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EventProcessingStatus status;
}
