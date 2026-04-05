package io.devflow.application.query;

import io.devflow.domain.codehost.CodeChangeStatus;
import java.time.Instant;

public record DashboardCodeChangeView(
    String repository,
    String externalId,
    String url,
    String targetBranch,
    CodeChangeStatus status,
    Instant createdAt
) {
}
