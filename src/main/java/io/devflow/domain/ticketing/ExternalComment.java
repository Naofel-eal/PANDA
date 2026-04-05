package io.devflow.domain.ticketing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ExternalComment {

    private final UUID id;
    private final String sourceSystem;
    private final String commentId;
    private final String parentType;
    private final String parentId;
    private final String authorId;
    private final String payloadHash;
    private final Instant commentCreatedAt;
    private final Instant commentUpdatedAt;
    private final Instant firstSeenAt;
    private final Instant lastSeenAt;

    private ExternalComment(
        UUID id,
        String sourceSystem,
        String commentId,
        String parentType,
        String parentId,
        String authorId,
        String payloadHash,
        Instant commentCreatedAt,
        Instant commentUpdatedAt,
        Instant firstSeenAt,
        Instant lastSeenAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sourceSystem = Objects.requireNonNull(sourceSystem, "sourceSystem must not be null");
        this.commentId = Objects.requireNonNull(commentId, "commentId must not be null");
        this.parentType = Objects.requireNonNull(parentType, "parentType must not be null");
        this.parentId = Objects.requireNonNull(parentId, "parentId must not be null");
        this.authorId = authorId;
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash must not be null");
        this.commentCreatedAt = commentCreatedAt;
        this.commentUpdatedAt = commentUpdatedAt;
        this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
        this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
    }

    public static ExternalComment register(
        UUID id,
        String sourceSystem,
        IncomingComment comment,
        String payloadHash,
        Instant now
    ) {
        return new ExternalComment(
            id,
            sourceSystem,
            comment.id(),
            comment.parentType(),
            comment.parentId(),
            comment.authorId(),
            payloadHash,
            comment.createdAt(),
            comment.updatedAt(),
            now,
            now
        );
    }

    public static ExternalComment rehydrate(
        UUID id,
        String sourceSystem,
        String commentId,
        String parentType,
        String parentId,
        String authorId,
        String payloadHash,
        Instant commentCreatedAt,
        Instant commentUpdatedAt,
        Instant firstSeenAt,
        Instant lastSeenAt
    ) {
        return new ExternalComment(
            id,
            sourceSystem,
            commentId,
            parentType,
            parentId,
            authorId,
            payloadHash,
            commentCreatedAt,
            commentUpdatedAt,
            firstSeenAt,
            lastSeenAt
        );
    }

    public ExternalComment markDuplicateSeen(Instant now) {
        return new ExternalComment(
            id,
            sourceSystem,
            commentId,
            parentType,
            parentId,
            authorId,
            payloadHash,
            commentCreatedAt,
            commentUpdatedAt,
            firstSeenAt,
            Objects.requireNonNull(now, "now must not be null")
        );
    }

    public ExternalComment update(String authorId, String payloadHash, Instant updatedAt, Instant now) {
        return new ExternalComment(
            id,
            sourceSystem,
            commentId,
            parentType,
            parentId,
            authorId,
            Objects.requireNonNull(payloadHash, "payloadHash must not be null"),
            commentCreatedAt,
            updatedAt,
            firstSeenAt,
            Objects.requireNonNull(now, "now must not be null")
        );
    }
}
