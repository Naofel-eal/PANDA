package io.devflow.infrastructure.http.request;

import jakarta.validation.constraints.NotBlank;

public record CodeChangeHttpPayload(
    @NotBlank String system,
    @NotBlank String externalId,
    String repository,
    String url,
    String sourceBranch,
    String targetBranch
) {
}
