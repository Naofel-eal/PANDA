package io.devflow.infrastructure.http.request;

import jakarta.validation.constraints.NotBlank;

public record DeploymentHttpPayload(
    @NotBlank String environment,
    String branch,
    String url
) {
}
