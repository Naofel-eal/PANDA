package io.devflow.domain.ticketing;

import java.time.Instant;

public record IncomingComment(
    String id,
    String parentType,
    String parentId,
    String authorId,
    String body,
    Instant createdAt,
    Instant updatedAt
) {
}
