package io.devflow.infrastructure.http.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record WorkItemHttpPayload(
    @NotBlank String key,
    String type,
    String title,
    String description,
    String status,
    String url,
    List<String> labels,
    List<String> repositories
) {
}
