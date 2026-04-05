package io.devflow.infrastructure.http.request;

import jakarta.validation.constraints.NotBlank;

public record BusinessValidationHttpPayload(
    @NotBlank String result,
    String summary
) {
}
