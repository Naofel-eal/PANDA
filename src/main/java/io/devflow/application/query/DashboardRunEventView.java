package io.devflow.application.query;

import io.devflow.domain.agent.AgentEventType;
import java.time.Instant;

public record DashboardRunEventView(
    AgentEventType type,
    Instant occurredAt,
    String summary
) {
}
