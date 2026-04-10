package io.nud.application.ticketing.port;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nud.application.command.ticketing.CommentWorkItemCommand;
import io.nud.application.command.ticketing.TransitionWorkItemCommand;
import io.nud.domain.model.ticketing.WorkItemTransitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketingPortTest {

    @Test
    @DisplayName("Given a ticketing port with no specific lookup support when NUD asks for ticket details then empty defaults are returned")
    void givenATicketingPortWithNoSpecificLookupSupport_whenNUDAsksForTicketDetails_thenEmptyDefaultsAreReturned() {
        TicketingPort ticketingPort = new TicketingPort() {
            @Override
            public void comment(CommentWorkItemCommand command) {
            }

            @Override
            public void transition(TransitionWorkItemCommand command) {
            }
        };

        assertTrue(ticketingPort.loadWorkItem("jira", "SCRUM-1").isEmpty());
        assertTrue(ticketingPort.listComments("jira", "SCRUM-1").isEmpty());
    }

    @Test
    @DisplayName("Given a minimal ticketing port when NUD uses write operations then the contract stays business-focused")
    void givenAMinimalTicketingPort_whenNUDUsesWriteOperations_thenTheContractStaysBusinessFocused() {
        TicketingPort ticketingPort = new TicketingPort() {
            @Override
            public void comment(CommentWorkItemCommand command) {
                assertTrue(command.comment().contains("blocked"));
            }

            @Override
            public void transition(TransitionWorkItemCommand command) {
                assertTrue(command.target() == WorkItemTransitionTarget.BLOCKED);
            }
        };

        ticketingPort.comment(new CommentWorkItemCommand("jira", "SCRUM-2", "Ticket blocked pending details", "BLOCKED"));
        ticketingPort.transition(new TransitionWorkItemCommand("jira", "SCRUM-2", WorkItemTransitionTarget.BLOCKED, "BLOCKED"));
    }
}
