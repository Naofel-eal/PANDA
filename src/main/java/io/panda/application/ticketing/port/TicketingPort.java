package io.panda.application.ticketing.port;

import io.panda.application.command.ticketing.CommentWorkItemCommand;
import io.panda.application.command.ticketing.TransitionWorkItemCommand;
import io.panda.domain.model.ticketing.IncomingComment;
import io.panda.domain.model.ticketing.WorkItem;
import java.util.List;
import java.util.Optional;

public interface TicketingPort {

    void comment(CommentWorkItemCommand command);
    void transition(TransitionWorkItemCommand command);

    default Optional<WorkItem> loadWorkItem(String workItemSystem, String workItemKey) {
        return Optional.empty();
    }

    default List<IncomingComment> listComments(String workItemSystem, String workItemKey) {
        return List.of();
    }

    default boolean isTerminalStatus(String workItemSystem, String workItemKey) {
        return loadWorkItem(workItemSystem, workItemKey)
            .map(wi -> isTerminalStatus(wi.status()))
            .orElse(false);
    }

    default boolean isTerminalStatus(String status) {
        return false;
    }

    default void claimWorkItem(String workItemSystem, String workItemKey) {
    }
}
