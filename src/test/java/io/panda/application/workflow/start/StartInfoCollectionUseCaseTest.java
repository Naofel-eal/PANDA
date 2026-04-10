package io.panda.application.workflow.start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.panda.application.agent.dispatch.DispatchAgentRunUseCase;
import io.panda.application.workflow.InMemoryWorkflowHolder;
import io.panda.domain.model.ticketing.IncomingComment;
import io.panda.domain.model.ticketing.WorkItem;
import io.panda.domain.model.workflow.Workflow;
import io.panda.domain.model.workflow.WorkflowPhase;
import io.panda.support.ReflectionTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StartInfoCollectionUseCaseTest {

    @Test
    @DisplayName("Given a ready ticket when PANDA starts work then information collection is dispatched with the business snapshot")
    void givenReadyTicket_whenPANDAStartsWork_thenInformationCollectionIsDispatchedWithBusinessSnapshot() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        RecordingDispatchAgentRunUseCase dispatcher = new RecordingDispatchAgentRunUseCase();
        StartInfoCollectionUseCase useCase = new StartInfoCollectionUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "dispatchAgentRunUseCase", dispatcher);

        WorkItem workItem = workItem("SCRUM-1");
        List<IncomingComment> comments = List.of(comment("More context"));

        useCase.execute("jira", workItem, comments);

        assertNotNull(dispatcher.workflow);
        assertEquals(WorkflowPhase.INFORMATION_COLLECTION, dispatcher.workflow.phase());
        assertEquals("Analyze work item SCRUM-1 and prepare an implementation plan", dispatcher.objective);
        assertEquals("SCRUM-1", dispatcher.snapshot.get("workItemKey"));
        assertEquals(workItem, dispatcher.snapshot.get("workItem"));
        assertEquals(comments, dispatcher.snapshot.get("workItemComments"));
        assertTrue(holder.isBusy());
    }

    @Test
    @DisplayName("Given a dispatch failure when PANDA starts work then the workflow is cleared to avoid a stuck ticket")
    void givenDispatchFailure_whenPANDAStartsWork_thenWorkflowIsClearedToAvoidAStuckTicket() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        StartInfoCollectionUseCase useCase = new StartInfoCollectionUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "dispatchAgentRunUseCase", new FailingDispatchAgentRunUseCase());

        useCase.execute("jira", workItem("SCRUM-2"), List.of());

        assertFalse(holder.isBusy());
    }

    private WorkItem workItem(String key) {
        return new WorkItem(key, "Task", "Title", "Description", "To Do", "https://jira.example/browse/" + key, List.of(), List.of(), Instant.now());
    }

    private IncomingComment comment(String body) {
        return new IncomingComment("1", "WORK_ITEM", "SCRUM-1", "user", body, Instant.now(), null);
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
