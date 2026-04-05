package io.devflow.application.port.ticketing;

import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.command.ticketing.TransitionWorkItemCommand;
import io.devflow.domain.ticketing.IncomingComment;
import io.devflow.domain.ticketing.WorkItem;
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
}
