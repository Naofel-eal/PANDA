package io.devflow.domain.model.ticketing;

public record WorkItemRef(
    String system,
    String key,
    String url
) {
}
