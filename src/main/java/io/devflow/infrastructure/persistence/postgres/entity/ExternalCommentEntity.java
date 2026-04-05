package io.devflow.infrastructure.persistence.postgres.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_comment")
public class ExternalCommentEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "source_system", nullable = false)
    public String sourceSystem;

    @Column(name = "comment_id", nullable = false)
    public String commentId;

    @Column(name = "parent_type", nullable = false)
    public String parentType;

    @Column(name = "parent_id", nullable = false)
    public String parentId;

    @Column(name = "author_id")
    public String authorId;

    @Column(name = "payload_hash")
    public String payloadHash;

    @Column(name = "comment_created_at")
    public Instant commentCreatedAt;

    @Column(name = "comment_updated_at")
    public Instant commentUpdatedAt;

    @Column(name = "first_seen_at", nullable = false)
    public Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    public Instant lastSeenAt;
}
