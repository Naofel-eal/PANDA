package io.devflow.application.ticketing.port;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.command.ticketing.TransitionWorkItemCommand;
import io.devflow.domain.model.ticketing.WorkItemTransitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketingPortTest {

    @Test
    @DisplayName("Given a ticketing port with no specific lookup support when DevFlow asks for ticket details then empty defaults are returned")
    void givenATicketingPortWithNoSpecificLookupSupport_whenDevFlowAsksForTicketDetails_thenEmptyDefaultsAreReturned() {
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
    @DisplayName("Given a minimal ticketing port when DevFlow uses write operations then the contract stays business-focused")
    void givenAMinimalTicketingPort_whenDevFlowUsesWriteOperations_thenTheContractStaysBusinessFocused() {
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
