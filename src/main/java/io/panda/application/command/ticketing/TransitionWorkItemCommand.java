package io.panda.application.command.ticketing;

import io.panda.domain.model.ticketing.WorkItemTransitionTarget;

public record TransitionWorkItemCommand(
    String workItemSystem,
    String workItemKey,
    WorkItemTransitionTarget target,
    String reasonCode
) {
}
