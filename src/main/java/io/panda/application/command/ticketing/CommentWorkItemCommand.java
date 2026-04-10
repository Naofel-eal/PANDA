package io.panda.application.command.ticketing;

public record CommentWorkItemCommand(
    String workItemSystem,
    String workItemKey,
    String comment,
    String reasonCode
) {
}
