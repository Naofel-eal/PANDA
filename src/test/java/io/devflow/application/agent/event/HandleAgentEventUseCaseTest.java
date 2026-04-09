package io.devflow.application.agent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.application.agent.dispatch.DispatchAgentRunUseCase;
import io.devflow.application.codehost.publish.PublishCodeChangesUseCase;
import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.command.ticketing.TransitionWorkItemCommand;
import io.devflow.application.ticketing.port.TicketingPort;
import io.devflow.application.workflow.port.WorkflowHolder;
import io.devflow.domain.model.agent.AgentEvent;
import io.devflow.domain.model.agent.AgentEventType;
import io.devflow.domain.model.codehost.CodeChangeRef;
import io.devflow.domain.model.ticketing.IncomingComment;
import io.devflow.domain.model.ticketing.WorkItem;
import io.devflow.domain.model.ticketing.WorkItemTransitionTarget;
import io.devflow.domain.model.workflow.BlockerType;
import io.devflow.domain.model.workflow.Workflow;
import io.devflow.domain.model.workflow.WorkflowPhase;
import io.devflow.support.ReflectionTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HandleAgentEventUseCaseTest {

    @Test
    @DisplayName("Given no active workflow when an agent event arrives then DevFlow ignores it")
    void givenNoActiveWorkflow_whenAnAgentEventArrives_thenDevFlowIgnoresIt() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, new RecordingTicketingPort(), new RecordingDispatchUseCase(), new RecordingPublishCodeChangesUseCase());

        boolean handled = useCase.execute(event(UUID.randomUUID(), AgentEventType.RUN_STARTED, WorkflowPhase.IMPLEMENTATION, null, Map.of(), Map.of()));

        assertFalse(handled);
    }

    @Test
    @DisplayName("Given an event for another run when it arrives then DevFlow ignores it to protect the current ticket")
    void givenAnEventForAnotherRun_whenItArrives_thenDevFlowIgnoresItToProtectTheCurrentTicket() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        workflowHolder.start(workflow(WorkflowPhase.IMPLEMENTATION));
        HandleAgentEventUseCase useCase = useCase(workflowHolder, new RecordingTicketingPort(), new RecordingDispatchUseCase(), new RecordingPublishCodeChangesUseCase());

        boolean handled = useCase.execute(event(UUID.randomUUID(), AgentEventType.RUN_STARTED, WorkflowPhase.IMPLEMENTATION, null, Map.of(), Map.of()));

        assertFalse(handled);
    }

    @Test
    @DisplayName("Given a started run when the event arrives then DevFlow moves the ticket into progress")
    void givenAStartedRun_whenTheEventArrives_thenDevFlowMovesTheTicketIntoProgress() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.IMPLEMENTATION);
        workflowHolder.start(workflow);
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, ticketingPort, new RecordingDispatchUseCase(), new RecordingPublishCodeChangesUseCase());

        boolean handled = useCase.execute(event(workflow.agentRunId(), AgentEventType.RUN_STARTED, WorkflowPhase.IMPLEMENTATION, null, Map.of(), Map.of()));

        assertTrue(handled);
        assertEquals(WorkItemTransitionTarget.IN_PROGRESS, ticketingPort.transitions.getFirst().target());
        assertEquals("AGENT_RUN_STARTED", ticketingPort.transitions.getFirst().reasonCode());
    }

    @Test
    @DisplayName("Given a progress update when the event arrives then DevFlow acknowledges it without changing the ticket state")
    void givenAProgressUpdate_whenTheEventArrives_thenDevFlowAcknowledgesItWithoutChangingTheTicketState() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.IMPLEMENTATION);
        workflowHolder.start(workflow);
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, ticketingPort, new RecordingDispatchUseCase(), new RecordingPublishCodeChangesUseCase());

        boolean handled = useCase.execute(event(workflow.agentRunId(), AgentEventType.PROGRESS_REPORTED, WorkflowPhase.IMPLEMENTATION, null, Map.of(), Map.of()));

        assertTrue(handled);
        assertTrue(ticketingPort.transitions.isEmpty());
        assertTrue(ticketingPort.comments.isEmpty());
    }

    @Test
    @DisplayName("Given a clarification request when the event arrives then DevFlow blocks the ticket, comments the need and clears the workflow")
    void givenAClarificationRequest_whenTheEventArrives_thenDevFlowBlocksTheTicketCommentsTheNeedAndClearsTheWorkflow() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.INFORMATION_COLLECTION);
        workflowHolder.start(workflow);
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, ticketingPort, new RecordingDispatchUseCase(), new RecordingPublishCodeChangesUseCase());

        useCase.execute(event(
            workflow.agentRunId(),
            AgentEventType.INPUT_REQUIRED,
            WorkflowPhase.INFORMATION_COLLECTION,
            BlockerType.MISSING_TICKET_INFORMATION,
            Map.of(),
            Map.of("missingInformation", List.of("description"))
        ));

        assertEquals(WorkItemTransitionTarget.BLOCKED, ticketingPort.transitions.getFirst().target());
        assertTrue(ticketingPort.comments.getFirst().comment().contains("Clarification needed before continuing"));
        assertEquals(1, workflowHolder.clearCount);
    }

    @Test
    @DisplayName("Given an implementation completion when the event arrives then DevFlow publishes the changes for review")
    void givenAnImplementationCompletion_whenTheEventArrives_thenDevFlowPublishesTheChangesForReview() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.IMPLEMENTATION);
        workflowHolder.start(workflow);
        RecordingPublishCodeChangesUseCase publishUseCase = new RecordingPublishCodeChangesUseCase();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, new RecordingTicketingPort(), new RecordingDispatchUseCase(), publishUseCase);

        useCase.execute(event(workflow.agentRunId(), AgentEventType.COMPLETED, WorkflowPhase.IMPLEMENTATION, null, Map.of(), Map.of()));

        assertEquals(workflow, publishUseCase.workflow);
        assertFalse(publishUseCase.skipTransitionToReview);
    }

    @Test
    @DisplayName("Given a completed done phase when the event arrives then DevFlow closes the workflow without publishing anything")
    void givenACompletedDonePhase_whenTheEventArrives_thenDevFlowClosesTheWorkflowWithoutPublishingAnything() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.DONE);
        workflowHolder.start(workflow);
        RecordingPublishCodeChangesUseCase publishUseCase = new RecordingPublishCodeChangesUseCase();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, new RecordingTicketingPort(), new RecordingDispatchUseCase(), publishUseCase);

        useCase.execute(event(workflow.agentRunId(), AgentEventType.COMPLETED, WorkflowPhase.DONE, null, Map.of(), Map.of()));

        assertEquals(1, workflowHolder.clearCount);
        assertNull(publishUseCase.workflow);
    }

    @Test
    @DisplayName("Given a failed run when the event arrives then DevFlow blocks the ticket, posts the failure and clears the workflow")
    void givenAFailedRun_whenTheEventArrives_thenDevFlowBlocksTheTicketPostsTheFailureAndClearsTheWorkflow() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.IMPLEMENTATION);
        workflowHolder.start(workflow);
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, ticketingPort, new RecordingDispatchUseCase(), new RecordingPublishCodeChangesUseCase());

        useCase.execute(new AgentEvent(
            "event-1",
            workflow.workflowId(),
            workflow.agentRunId(),
            AgentEventType.FAILED,
            Instant.parse("2026-04-09T10:00:00Z"),
            "provider-1",
            "Push failed",
            null,
            "push failed",
            null,
            null,
            null,
            Map.of(),
            Map.of("error", "remote rejected")
        ));

        assertEquals(WorkItemTransitionTarget.BLOCKED, ticketingPort.transitions.getFirst().target());
        assertTrue(ticketingPort.comments.getFirst().comment().contains("Implementation failed."));
        assertEquals(1, workflowHolder.clearCount);
    }

    @Test
    @DisplayName("Given ticketing operations fail while handling a failure when the event arrives then DevFlow still clears the workflow")
    void givenTicketingOperationsFailWhileHandlingAFailure_whenTheEventArrives_thenDevFlowStillClearsTheWorkflow() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.IMPLEMENTATION);
        workflowHolder.start(workflow);
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        ticketingPort.failTransition = true;
        ticketingPort.failComment = true;
        HandleAgentEventUseCase useCase = useCase(workflowHolder, ticketingPort, new RecordingDispatchUseCase(), new RecordingPublishCodeChangesUseCase());

        useCase.execute(new AgentEvent(
            "event-1",
            workflow.workflowId(),
            workflow.agentRunId(),
            AgentEventType.FAILED,
            Instant.parse("2026-04-09T10:00:00Z"),
            "provider-1",
            "Push failed",
            null,
            "push failed",
            null,
            null,
            null,
            Map.of(),
            Map.of("error", "remote rejected")
        ));

        assertEquals(1, workflowHolder.clearCount);
    }

    @Test
    @DisplayName("Given a cancelled run when the event arrives then DevFlow simply clears the workflow")
    void givenACancelledRun_whenTheEventArrives_thenDevFlowSimplyClearsTheWorkflow() {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.IMPLEMENTATION);
        workflowHolder.start(workflow);
        HandleAgentEventUseCase useCase = useCase(workflowHolder, new RecordingTicketingPort(), new RecordingDispatchUseCase(), new RecordingPublishCodeChangesUseCase());

        useCase.execute(event(workflow.agentRunId(), AgentEventType.CANCELLED, WorkflowPhase.IMPLEMENTATION, null, Map.of(), Map.of()));

        assertEquals(1, workflowHolder.clearCount);
    }

    @Test
    @DisplayName("Given information collection completion when the event arrives then DevFlow chains automatically into implementation with the latest ticket context")
    void givenInformationCollectionCompletion_whenTheEventArrives_thenDevFlowChainsAutomaticallyIntoImplementationWithTheLatestTicketContext() throws Exception {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.INFORMATION_COLLECTION);
        workflowHolder.start(workflow);
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        ticketingPort.workItem = new WorkItem(
            workflow.ticketKey(),
            "Story",
            "Implement SCRUM-40",
            "Description",
            "In Progress",
            "https://jira.example/browse/" + workflow.ticketKey(),
            List.of("workflow"),
            List.of("acme/api"),
            null
        );
        ticketingPort.commentsToReturn = List.of(new IncomingComment(
            "1",
            "WORK_ITEM",
            workflow.ticketKey(),
            "user-1",
            "Business context",
            Instant.parse("2026-04-09T10:00:00Z"),
            null
        ));
        RecordingDispatchUseCase dispatchUseCase = new RecordingDispatchUseCase();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, ticketingPort, dispatchUseCase, new RecordingPublishCodeChangesUseCase());

        useCase.execute(event(workflow.agentRunId(), AgentEventType.COMPLETED, WorkflowPhase.INFORMATION_COLLECTION, null, Map.of(), Map.of()));

        waitFor(() -> dispatchUseCase.workflow != null, Duration.ofSeconds(5));
        assertEquals(WorkflowPhase.IMPLEMENTATION, dispatchUseCase.workflow.phase());
        assertEquals("Implement work item " + workflow.ticketKey(), dispatchUseCase.objective);
        assertEquals(workflow.ticketKey(), dispatchUseCase.snapshot.get("workItemKey"));
        assertNotNull(dispatchUseCase.snapshot.get("workItem"));
        assertNotNull(dispatchUseCase.snapshot.get("workItemComments"));
    }

    @Test
    @DisplayName("Given information collection completion but ticket context cannot be reloaded when the event arrives then DevFlow still chains implementation with the available minimum snapshot")
    void givenInformationCollectionCompletionButTicketContextCannotBeReloaded_whenTheEventArrives_thenDevFlowStillChainsImplementationWithTheAvailableMinimumSnapshot() throws Exception {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.INFORMATION_COLLECTION);
        workflowHolder.start(workflow);
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        ticketingPort.failLoadWorkItem = true;
        ticketingPort.failListComments = true;
        RecordingDispatchUseCase dispatchUseCase = new RecordingDispatchUseCase();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, ticketingPort, dispatchUseCase, new RecordingPublishCodeChangesUseCase());

        useCase.execute(event(workflow.agentRunId(), AgentEventType.COMPLETED, WorkflowPhase.INFORMATION_COLLECTION, null, Map.of(), Map.of()));

        waitFor(() -> dispatchUseCase.workflow != null, Duration.ofSeconds(5));
        assertEquals(workflow.ticketKey(), dispatchUseCase.snapshot.get("workItemKey"));
        assertNull(dispatchUseCase.snapshot.get("workItem"));
        assertNull(dispatchUseCase.snapshot.get("workItemComments"));
    }

    @Test
    @DisplayName("Given information collection completion but dispatch fails when the event arrives then DevFlow blocks the ticket with a dispatch explanation")
    void givenInformationCollectionCompletionButDispatchFails_whenTheEventArrives_thenDevFlowBlocksTheTicketWithADispatchExplanation() throws Exception {
        RecordingWorkflowHolder workflowHolder = new RecordingWorkflowHolder();
        Workflow workflow = workflow(WorkflowPhase.INFORMATION_COLLECTION);
        workflowHolder.start(workflow);
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        FailingDispatchUseCase dispatchUseCase = new FailingDispatchUseCase();
        HandleAgentEventUseCase useCase = useCase(workflowHolder, ticketingPort, dispatchUseCase, new RecordingPublishCodeChangesUseCase());

        useCase.execute(event(workflow.agentRunId(), AgentEventType.COMPLETED, WorkflowPhase.INFORMATION_COLLECTION, null, Map.of(), Map.of()));

        waitFor(() -> !ticketingPort.transitions.isEmpty(), Duration.ofSeconds(5));
        assertEquals(WorkItemTransitionTarget.BLOCKED, ticketingPort.transitions.getFirst().target());
        assertTrue(ticketingPort.comments.getFirst().comment().contains("failed to start the implementation phase"));
        assertEquals(1, workflowHolder.clearCount);
    }

    private HandleAgentEventUseCase useCase(
        RecordingWorkflowHolder workflowHolder,
        RecordingTicketingPort ticketingPort,
        DispatchAgentRunUseCase dispatchUseCase,
        PublishCodeChangesUseCase publishUseCase
    ) {
        HandleAgentEventUseCase useCase = new HandleAgentEventUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", workflowHolder);
        ReflectionTestSupport.setField(useCase, "ticketingPort", ticketingPort);
        ReflectionTestSupport.setField(useCase, "dispatchAgentRunUseCase", dispatchUseCase);
        ReflectionTestSupport.setField(useCase, "publishCodeChangesUseCase", publishUseCase);
        return useCase;
    }

    private Workflow workflow(WorkflowPhase phase) {
        return Workflow.start(UUID.randomUUID(), UUID.randomUUID(), "jira", "SCRUM-40", phase, "Objective");
    }

    private AgentEvent event(
        UUID agentRunId,
        AgentEventType type,
        WorkflowPhase phase,
        BlockerType blockerType,
        Map<String, Object> artifacts,
        Map<String, Object> details
    ) {
        return new AgentEvent(
            "event-1",
            UUID.randomUUID(),
            agentRunId,
            type,
            Instant.parse("2026-04-09T10:00:00Z"),
            "provider-1",
            "Summary",
            blockerType,
            "reason",
            null,
            null,
            null,
            artifacts,
            details
        );
    }

    private void waitFor(Check check, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.done()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition was not met within " + timeout);
    }

    @FunctionalInterface
    private interface Check {
        boolean done();
    }

    private static final class RecordingWorkflowHolder implements WorkflowHolder {
        private Workflow current;
        private int clearCount;

        @Override
        public boolean isBusy() {
            return current != null;
        }

        @Override
        public boolean isStale(Duration maxDuration) {
            return false;
        }

        @Override
        public Workflow current() {
            return current;
        }

        @Override
        public Workflow start(Workflow workflow) {
            current = workflow;
            return workflow;
        }

        @Override
        public void clear() {
            current = null;
            clearCount++;
        }

        @Override
        public void clearIfMatches(UUID agentRunId) {
            if (current != null && current.belongsTo(agentRunId)) {
                clear();
            }
        }

        @Override
        public Workflow replacePhase(WorkflowPhase newPhase, UUID newAgentRunId) {
            current = current.chainToPhase(newPhase, newAgentRunId);
            return current;
        }

        @Override
        public void addPublishedPR(CodeChangeRef codeChange) {
            current.addPublishedPR(codeChange);
        }
    }

    private static final class RecordingTicketingPort implements TicketingPort {
        private final List<TransitionWorkItemCommand> transitions = new ArrayList<>();
        private final List<CommentWorkItemCommand> comments = new ArrayList<>();
        private WorkItem workItem;
        private List<IncomingComment> commentsToReturn = List.of();
        private boolean failTransition;
        private boolean failComment;
        private boolean failLoadWorkItem;
        private boolean failListComments;

        @Override
        public void comment(CommentWorkItemCommand command) {
            if (failComment) {
                throw new IllegalStateException("comment failed");
            }
            comments.add(command);
        }

        @Override
        public void transition(TransitionWorkItemCommand command) {
            if (failTransition) {
                throw new IllegalStateException("transition failed");
            }
            transitions.add(command);
        }

        @Override
        public Optional<WorkItem> loadWorkItem(String workItemSystem, String workItemKey) {
            if (failLoadWorkItem) {
                throw new IllegalStateException("load failed");
            }
            return Optional.ofNullable(workItem);
        }

        @Override
        public List<IncomingComment> listComments(String workItemSystem, String workItemKey) {
            if (failListComments) {
                throw new IllegalStateException("comments failed");
            }
            return commentsToReturn;
        }
    }

    private static final class RecordingDispatchUseCase extends DispatchAgentRunUseCase {
        private Workflow workflow;
        private String objective;
        private Map<String, Object> snapshot;

        @Override
        public void execute(Workflow workflow, String objective, Map<String, Object> contextData) {
            this.workflow = workflow;
            this.objective = objective;
            this.snapshot = new LinkedHashMap<>(contextData);
        }
    }

    private static final class FailingDispatchUseCase extends DispatchAgentRunUseCase {
        @Override
        public void execute(Workflow workflow, String objective, Map<String, Object> contextData) {
            throw new IllegalStateException("dispatch failed");
        }
    }

    private static final class RecordingPublishCodeChangesUseCase extends PublishCodeChangesUseCase {
        private Workflow workflow;
        private AgentEvent event;
        private boolean skipTransitionToReview;

        @Override
        public void execute(Workflow workflow, AgentEvent event, boolean skipTransitionToReview) {
            this.workflow = workflow;
            this.event = event;
            this.skipTransitionToReview = skipTransitionToReview;
        }
    }
}
