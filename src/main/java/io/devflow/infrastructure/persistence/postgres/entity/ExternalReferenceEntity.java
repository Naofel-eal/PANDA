package io.devflow.infrastructure.persistence.postgres.entity;

import io.devflow.domain.codehost.ExternalReferenceType;
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
@Table(name = "external_reference")
public class ExternalReferenceEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "workflow_id", nullable = false)
    public UUID workflowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false)
    public ExternalReferenceType refType;

    @Column(nullable = false)
    public String system;

    @Column(name = "external_id", nullable = false)
    public String externalId;

    @Column
    public String url;

    @Column(name = "metadata_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    public String metadataJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
