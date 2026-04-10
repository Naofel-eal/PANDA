package io.nud.application.command.ticketing;

import io.nud.domain.model.ticketing.WorkItemTransitionTarget;

public record TransitionWorkItemCommand(
    String workItemSystem,
    String workItemKey,
    WorkItemTransitionTarget target,
    String reasonCode
) {
}
