package io.devflow.infrastructure.persistence.postgres.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "workflow_event")
public class WorkflowEventEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "workflow_id", nullable = false)
    public UUID workflowId;

    @Column(name = "event_type", nullable = false)
    public String eventType;

    @Column(name = "event_payload_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    public String eventPayloadJson;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    @Column(name = "source_system")
    public String sourceSystem;

    @Column(name = "source_event_id")
    public String sourceEventId;
}
