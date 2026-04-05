package io.devflow.application.port.ticketing;

import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.command.ticketing.TransitionWorkItemCommand;

public interface TicketingPort {

    void comment(CommentWorkItemCommand command);

    void transition(TransitionWorkItemCommand command);
}
