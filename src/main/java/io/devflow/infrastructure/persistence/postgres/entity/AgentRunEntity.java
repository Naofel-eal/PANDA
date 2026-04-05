package io.devflow.infrastructure.persistence.postgres.entity;

import io.devflow.domain.agent.AgentRunStatus;
import io.devflow.domain.workflow.WorkflowPhase;
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
@Table(name = "agent_run")
public class AgentRunEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "workflow_id", nullable = false)
    public UUID workflowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public WorkflowPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AgentRunStatus status;

    @Column(name = "input_snapshot_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    public String inputSnapshotJson;

    @Column(name = "provider_run_ref")
    public String providerRunRef;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "ended_at")
    public Instant endedAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
