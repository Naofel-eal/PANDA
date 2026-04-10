package io.nud.application.codehost.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nud.application.agent.dispatch.DispatchAgentRunUseCase;
import io.nud.application.ticketing.port.TicketingPort;
import io.nud.application.workflow.InMemoryWorkflowHolder;
import io.nud.domain.model.codehost.CodeChangeRef;
import io.nud.domain.model.ticketing.IncomingComment;
import io.nud.domain.model.ticketing.WorkItem;
import io.nud.domain.model.workflow.Workflow;
import io.nud.domain.model.workflow.WorkflowPhase;
import io.nud.support.ReflectionTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HandleReviewCommentUseCaseTest {

    @Test
    @DisplayName("Given review feedback when NUD prepares a fix then the technical validation run is dispatched with the review context")
    void givenReviewFeedback_whenNUDPreparesAFix_thenTechnicalValidationRunIsDispatchedWithTheReviewContext() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        RecordingDispatchAgentRunUseCase dispatcher = new RecordingDispatchAgentRunUseCase();
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        ticketingPort.workItem = new WorkItem("SCRUM-1", "Task", "Title", "Description", "To Review", "url", List.of(), List.of(), Instant.now());

        HandleReviewCommentUseCase useCase = new HandleReviewCommentUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "dispatchAgentRunUseCase", dispatcher);
        ReflectionTestSupport.setField(useCase, "ticketingPort", ticketingPort);

        CodeChangeRef codeChange = new CodeChangeRef("github", "repo#23", "Naofel-eal/front-test", "https://github.com/example/pull/23", "head", "main");
        List<IncomingComment> comments = List.of(new IncomingComment("1", "CODE_CHANGE", "repo#23", "reviewer", "Please adjust the text", Instant.now(), null));

        useCase.execute(new HandleReviewCommentUseCase.Command("jira", "SCRUM-1", "Naofel-eal/front-test", codeChange, comments));

        assertNotNull(dispatcher.workflow);
        assertEquals(WorkflowPhase.TECHNICAL_VALIDATION, dispatcher.workflow.phase());
        assertEquals(codeChange, dispatcher.snapshot.get("codeChange"));
        assertEquals(comments, dispatcher.snapshot.get("reviewComments"));
        assertEquals(List.of("Naofel-eal/front-test"), dispatcher.snapshot.get("repositories"));
        assertEquals(ticketingPort.workItem, dispatcher.snapshot.get("workItem"));
        assertTrue(holder.isBusy());
    }

    @Test
    @DisplayName("Given a dispatch problem when review feedback arrives then the workflow is cleared instead of blocking the system")
    void givenDispatchProblem_whenReviewFeedbackArrives_thenWorkflowIsClearedInsteadOfBlockingTheSystem() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        HandleReviewCommentUseCase useCase = new HandleReviewCommentUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "dispatchAgentRunUseCase", new FailingDispatchAgentRunUseCase());
        ReflectionTestSupport.setField(useCase, "ticketingPort", new RecordingTicketingPort());

        useCase.execute(new HandleReviewCommentUseCase.Command(
            "jira",
            "SCRUM-2",
            "Naofel-eal/front-test",
            new CodeChangeRef("github", "repo#24", "Naofel-eal/front-test", "url", "head", "main"),
            List.of(new IncomingComment("1", "CODE_CHANGE", "repo#24", "reviewer", "Please continue", Instant.now(), null))
        ));

        assertFalse(holder.isBusy());
    }

    @Test
    @DisplayName("Given the ticket cannot be reloaded when review feedback arrives then NUD still dispatches the fix with the review context only")
    void givenTheTicketCannotBeReloaded_whenReviewFeedbackArrives_thenNUDStillDispatchesTheFixWithTheReviewContextOnly() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        RecordingDispatchAgentRunUseCase dispatcher = new RecordingDispatchAgentRunUseCase();
        HandleReviewCommentUseCase useCase = new HandleReviewCommentUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "dispatchAgentRunUseCase", dispatcher);
        ReflectionTestSupport.setField(useCase, "ticketingPort", new TicketingPort() {
            @Override
            public void comment(io.nud.application.command.ticketing.CommentWorkItemCommand command) {
            }

            @Override
            public void transition(io.nud.application.command.ticketing.TransitionWorkItemCommand command) {
            }

            @Override
            public Optional<WorkItem> loadWorkItem(String workItemSystem, String workItemKey) {
                throw new IllegalStateException("jira unavailable");
            }
        });

        useCase.execute(new HandleReviewCommentUseCase.Command(
            "jira",
            "SCRUM-3",
            "Naofel-eal/front-test",
            new CodeChangeRef("github", "repo#25", "Naofel-eal/front-test", "url", "head", "main"),
            List.of(new IncomingComment("1", "CODE_CHANGE", "repo#25", "reviewer", "Please tighten the copy", Instant.now(), null))
        ));

        assertNotNull(dispatcher.workflow);
        assertFalse(dispatcher.snapshot.containsKey("workItem"));
        assertTrue(holder.isBusy());
    }

    private static final class RecordingDispatchAgentRunUseCase extends DispatchAgentRunUseCase {
        private Workflow workflow;
        private Map<String, Object> snapshot;

        @Override
        public void execute(Workflow workflow, String objective, Map<String, Object> contextData) {
            this.workflow = workflow;
            this.snapshot = contextData;
        }
    }

    private static final class FailingDispatchAgentRunUseCase extends DispatchAgentRunUseCase {
        @Override
        public void execute(Workflow workflow, String objective, Map<String, Object> contextData) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class RecordingTicketingPort implements TicketingPort {
        private WorkItem workItem;

        @Override
        public void comment(io.nud.application.command.ticketing.CommentWorkItemCommand command) {
        }

        @Override
        public void transition(io.nud.application.command.ticketing.TransitionWorkItemCommand command) {
        }

        @Override
        public Optional<WorkItem> loadWorkItem(String workItemSystem, String workItemKey) {
            return Optional.ofNullable(workItem);
        }
    }
}
