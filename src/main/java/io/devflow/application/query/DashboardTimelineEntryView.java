package io.devflow.application.query;

import java.time.Instant;

public record DashboardTimelineEntryView(
    Instant occurredAt,
    String category,
    String title,
    String summary
) {
}
