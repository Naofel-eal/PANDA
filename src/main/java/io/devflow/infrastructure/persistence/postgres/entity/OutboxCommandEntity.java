package io.devflow.infrastructure.persistence.postgres.entity;

import io.devflow.domain.agent.AgentCommandType;
import io.devflow.domain.messaging.OutboxCommandStatus;
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
@Table(name = "outbox_command")
public class OutboxCommandEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "workflow_id", nullable = false)
    public UUID workflowId;

    @Column(name = "agent_run_id")
    public UUID agentRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false)
    public AgentCommandType commandType;

    @Column(name = "payload_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    public String payloadJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "processed_at")
    public Instant processedAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public OutboxCommandStatus status;

    @Column(name = "failure_reason")
    public String failureReason;
}
