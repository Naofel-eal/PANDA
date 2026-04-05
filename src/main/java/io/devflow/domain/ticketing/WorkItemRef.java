package io.devflow.domain.ticketing;

public record WorkItemRef(
    String system,
    String key,
    String url
) {
}
