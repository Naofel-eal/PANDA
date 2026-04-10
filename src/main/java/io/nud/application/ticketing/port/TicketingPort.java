package io.nud.application.ticketing.port;

import io.nud.application.command.ticketing.CommentWorkItemCommand;
import io.nud.application.command.ticketing.TransitionWorkItemCommand;
import io.nud.domain.model.ticketing.IncomingComment;
import io.nud.domain.model.ticketing.WorkItem;
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
