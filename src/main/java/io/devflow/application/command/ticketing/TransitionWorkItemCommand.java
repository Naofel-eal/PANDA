package io.devflow.application.command.ticketing;

import io.devflow.domain.model.ticketing.WorkItemTransitionTarget;

public record TransitionWorkItemCommand(
    String workItemSystem,
    String workItemKey,
    WorkItemTransitionTarget target,
    String reasonCode
) {
}
