package io.panda.domain.model.ticketing;

public record WorkItemRef(
    String system,
    String key,
    String url
) {
}
