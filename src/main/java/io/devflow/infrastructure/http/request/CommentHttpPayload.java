package io.devflow.infrastructure.http.request;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record CommentHttpPayload(
    @NotBlank String id,
    @NotBlank String parentType,
    @NotBlank String parentId,
    String authorId,
    String body,
    Instant createdAt,
    Instant updatedAt
) {
}
