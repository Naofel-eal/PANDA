package io.devflow.application.workflow.resume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.application.agent.dispatch.DispatchAgentRunUseCase;
import io.devflow.application.workflow.InMemoryWorkflowHolder;
import io.devflow.domain.model.ticketing.IncomingComment;
import io.devflow.domain.model.ticketing.WorkItem;
import io.devflow.domain.model.workflow.Workflow;
import io.devflow.domain.model.workflow.WorkflowPhase;
import io.devflow.support.ReflectionTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResumeWorkflowUseCaseTest {

    @Test
    @DisplayName("Given a blocked or validation ticket when new business feedback arrives then the requested phase is dispatched")
    void givenBlockedOrValidationTicket_whenNewBusinessFeedbackArrives_thenRequestedPhaseIsDispatched() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        RecordingDispatchAgentRunUseCase dispatcher = new RecordingDispatchAgentRunUseCase();
        ResumeWorkflowUseCase useCase = new ResumeWorkflowUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "dispatchAgentRunUseCase", dispatcher);

        WorkItem workItem = new WorkItem("SCRUM-3", "Task", "Title", "Description", "Blocked", "url", List.of(), List.of(), Instant.now());
        List<IncomingComment> comments = List.of(new IncomingComment("1", "WORK_ITEM", "SCRUM-3", "user", "Please continue", Instant.now(), null));

        useCase.execute("jira", workItem, comments, WorkflowPhase.BUSINESS_VALIDATION);

        assertEquals(WorkflowPhase.BUSINESS_VALIDATION, dispatcher.workflow.phase());
        assertEquals("Analyze and implement work item SCRUM-3", dispatcher.objective);
        assertEquals(comments, dispatcher.snapshot.get("workItemComments"));
        assertTrue(holder.isBusy());
    }

    @Test
    @DisplayName("Given a dispatch failure when a paused ticket is resumed then the workflow is cleared safely")
    void givenDispatchFailure_whenPausedTicketIsResumed_thenWorkflowIsClearedSafely() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        ResumeWorkflowUseCase useCase = new ResumeWorkflowUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "dispatchAgentRunUseCase", new FailingDispatchAgentRunUseCase());

        WorkItem workItem = new WorkItem("SCRUM-4", "Task", "Title", "Description", "Blocked", "url", List.of(), List.of(), Instant.now());
        useCase.execute("jira", workItem, List.of(), WorkflowPhase.INFORMATION_COLLECTION);

        assertFalse(holder.isBusy());
    }

    private static final class RecordingDispatchAgentRunUseCase extends DispatchAgentRunUseCase {
        private Workflow workflow;
        private String objective;
        private Map<String, Object> snapshot;

        @Override
        public void execute(Workflow workflow, String objective, Map<String, Object> contextData) {
            this.workflow = workflow;
            this.objective = objective;
            this.snapshot = contextData;
        }
    }

    private static final class FailingDispatchAgentRunUseCase extends DispatchAgentRunUseCase {
        @Override
        public void execute(Workflow workflow, String objective, Map<String, Object> contextData) {
            throw new IllegalStateException("boom");
        }
    }
}
