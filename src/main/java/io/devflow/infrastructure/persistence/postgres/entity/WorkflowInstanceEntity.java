package io.devflow.infrastructure.persistence.postgres.entity;

import io.devflow.domain.workflow.WorkflowPhase;
import io.devflow.domain.workflow.WorkflowStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "workflow_instance")
public class WorkflowInstanceEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "workflow_definition_id", nullable = false)
    public String workflowDefinitionId;

    @Column(name = "work_item_system", nullable = false)
    public String workItemSystem;

    @Column(name = "work_item_key", nullable = false)
    public String workItemKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public WorkflowPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public WorkflowStatus status;

    @Column(name = "current_blocker_id")
    public UUID currentBlockerId;

    @Column(name = "context_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    public String contextJson;

    @Version
    @Column(nullable = false)
    public long version;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
