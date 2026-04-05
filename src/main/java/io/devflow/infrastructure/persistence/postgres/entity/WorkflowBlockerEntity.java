package io.devflow.infrastructure.persistence.postgres.entity;

import io.devflow.domain.workflow.BlockerStatus;
import io.devflow.domain.workflow.BlockerType;
import io.devflow.domain.workflow.RequestedFrom;
import io.devflow.domain.workflow.ResumeTrigger;
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
@Table(name = "workflow_blocker")
public class WorkflowBlockerEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "workflow_id", nullable = false)
    public UUID workflowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public WorkflowPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public BlockerType type;

    @Column(name = "reason_code")
    public String reasonCode;

    @Column(name = "summary", nullable = false)
    public String summary;

    @Column(name = "suggested_comment")
    public String suggestedComment;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_from", nullable = false)
    public RequestedFrom requestedFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "resume_trigger", nullable = false)
    public ResumeTrigger resumeTrigger;

    @Column(name = "details_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    public String detailsJson;

    @Column(name = "opened_at", nullable = false)
    public Instant openedAt;

    @Column(name = "resolved_at")
    public Instant resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public BlockerStatus status;
}
