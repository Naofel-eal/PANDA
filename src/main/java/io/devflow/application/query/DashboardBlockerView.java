package io.devflow.application.query;

import io.devflow.domain.workflow.BlockerStatus;
import io.devflow.domain.workflow.BlockerType;
import io.devflow.domain.workflow.RequestedFrom;
import java.time.Instant;

public record DashboardBlockerView(
    BlockerType type,
    String reasonCode,
    String summary,
    RequestedFrom requestedFrom,
    BlockerStatus status,
    Instant openedAt,
    Instant resolvedAt
) {
}
