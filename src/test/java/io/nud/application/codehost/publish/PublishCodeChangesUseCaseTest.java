package io.nud.application.codehost.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nud.application.codehost.port.CodeHostPort;
import io.nud.application.command.codehost.PublishCodeChangesCommand;
import io.nud.application.command.ticketing.CommentWorkItemCommand;
import io.nud.application.command.ticketing.TransitionWorkItemCommand;
import io.nud.application.ticketing.port.TicketingPort;
import io.nud.application.workflow.InMemoryWorkflowHolder;
import io.nud.domain.model.agent.AgentEvent;
import io.nud.domain.model.agent.AgentEventType;
import io.nud.domain.model.codehost.CodeChangeRef;
import io.nud.domain.model.ticketing.WorkItem;
import io.nud.domain.model.ticketing.WorkItemTransitionTarget;
import io.nud.domain.model.workflow.Workflow;
import io.nud.domain.model.workflow.WorkflowPhase;
import io.nud.support.ReflectionTestSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublishCodeChangesUseCaseTest {

    @Test
    @DisplayName("Given completed implementation when code changes are published then NUD opens review and records the resulting pull request")
    void givenCompletedImplementation_whenCodeChangesArePublished_thenNUDOpensReviewAndRecordsTheResultingPullRequest() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        Workflow workflow = Workflow.start(UUID.randomUUID(), UUID.randomUUID(), "jira", "SCRUM-1", WorkflowPhase.IMPLEMENTATION, "Implement");
        holder.start(workflow);

        RecordingCodeHostPort codeHostPort = new RecordingCodeHostPort();
        codeHostPort.configuredRepositories = List.of("Naofel-eal/front-test");
        codeHostPort.publishResult = List.of(new CodeChangeRef("github", "Naofel-eal/front-test#1", "Naofel-eal/front-test", "https://github.com/pr/1", "head", "main"));

        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        ticketingPort.workItem = new WorkItem("SCRUM-1", "Task", "Business title", "Description", "En cours", "url", List.of(), List.of(), Instant.now());

        PublishCodeChangesUseCase useCase = new PublishCodeChangesUseCase();
        ReflectionTestSupport.setField(useCase, "codeHostPort", codeHostPort);
        ReflectionTestSupport.setField(useCase, "ticketingPort", ticketingPort);
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);

        useCase.execute(workflow, completedEvent("Implemented helper text"), false);

        assertEquals("Business title", codeHostPort.publishCommand.workItemTitle());
        assertEquals(WorkItemTransitionTarget.TO_REVIEW, ticketingPort.transition.target());
        assertTrue(ticketingPort.comment.comment().contains("Implemented helper text"));
        assertNull(holder.current());
    }

    @Test
    @DisplayName("Given published review fixes when the ticket stays in review then NUD posts the outcome without moving the ticket again")
    void givenPublishedReviewFixes_whenTicketStaysInReview_thenNUDPostsTheOutcomeWithoutMovingTheTicketAgain() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        Workflow workflow = Workflow.start(UUID.randomUUID(), UUID.randomUUID(), "jira", "SCRUM-2", WorkflowPhase.TECHNICAL_VALIDATION, "Review");
        holder.start(workflow);

        RecordingCodeHostPort codeHostPort = new RecordingCodeHostPort();
        codeHostPort.configuredRepositories = List.of("Naofel-eal/front-test");
        codeHostPort.publishResult = List.of(new CodeChangeRef("github", "Naofel-eal/front-test#2", "Naofel-eal/front-test", "https://github.com/pr/2", "head", "main"));
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();

        PublishCodeChangesUseCase useCase = new PublishCodeChangesUseCase();
        ReflectionTestSupport.setField(useCase, "codeHostPort", codeHostPort);
        ReflectionTestSupport.setField(useCase, "ticketingPort", ticketingPort);
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);

        useCase.execute(workflow, completedEvent("Review fixes applied"), true);

        assertNull(ticketingPort.transition);
        assertEquals("REVIEW_ADDRESSED", ticketingPort.comment.reasonCode());
        assertNull(holder.current());
    }

    @Test
    @DisplayName("Given a publish failure when NUD cannot open a pull request then the ticket is blocked and the failure is explained")
    void givenPublishFailure_whenNUDCannotOpenPullRequest_thenTicketIsBlockedAndFailureIsExplained() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        Workflow workflow = Workflow.start(UUID.randomUUID(), UUID.randomUUID(), "jira", "SCRUM-3", WorkflowPhase.IMPLEMENTATION, "Implement");
        holder.start(workflow);

        PublishCodeChangesUseCase useCase = new PublishCodeChangesUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "codeHostPort", new CodeHostPort() {
            @Override
            public List<CodeChangeRef> publish(PublishCodeChangesCommand command) {
                throw new IllegalStateException("push failed");
            }

            @Override
            public List<String> configuredRepositories() {
                return List.of("Naofel-eal/front-test");
            }

            @Override
            public io.nud.domain.model.workspace.PreparedWorkspace prepareWorkspace(io.nud.application.command.workspace.PrepareWorkspaceCommand command) {
                throw new UnsupportedOperationException();
            }
        });
        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        ReflectionTestSupport.setField(useCase, "ticketingPort", ticketingPort);

        useCase.execute(workflow, completedEvent("Will fail"), false);

        assertEquals(WorkItemTransitionTarget.BLOCKED, ticketingPort.transition.target());
        assertTrue(ticketingPort.comment.comment().contains("publish failure"));
        assertNull(holder.current());
    }

    @Test
    @DisplayName("Given ticket lookup and Jira side effects fail when publication succeeds then NUD still publishes with safe fallbacks and clears the workflow")
    void givenTicketLookupAndJiraSideEffectsFail_whenPublicationSucceeds_thenNUDStillPublishesWithSafeFallbacksAndClearsTheWorkflow() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        Workflow workflow = Workflow.start(UUID.randomUUID(), UUID.randomUUID(), "jira", "SCRUM-4", WorkflowPhase.IMPLEMENTATION, "Implement");
        holder.start(workflow);

        RecordingCodeHostPort codeHostPort = new RecordingCodeHostPort();
        codeHostPort.configuredRepositories = List.of("Naofel-eal/front-test");
        codeHostPort.publishResult = List.of(new CodeChangeRef("github", "Naofel-eal/front-test#4", "Naofel-eal/front-test", "https://github.com/pr/4", "head", "main"));

        RecordingTicketingPort ticketingPort = new RecordingTicketingPort();
        ticketingPort.failLoad = true;
        ticketingPort.failTransition = true;
        ticketingPort.failComment = true;

        PublishCodeChangesUseCase useCase = new PublishCodeChangesUseCase();
        ReflectionTestSupport.setField(useCase, "codeHostPort", codeHostPort);
        ReflectionTestSupport.setField(useCase, "ticketingPort", ticketingPort);
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);

        useCase.execute(workflow, new AgentEvent(
            "event-2",
            UUID.randomUUID(),
            UUID.randomUUID(),
            AgentEventType.COMPLETED,
            Instant.now(),
            "provider:2",
            "Published despite ticketing trouble",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of()
        ), false);

        assertEquals("SCRUM-4", codeHostPort.publishCommand.workItemTitle());
        assertEquals(List.of("Naofel-eal/front-test"), codeHostPort.publishCommand.repositories());
        assertNull(holder.current());
    }

    private AgentEvent completedEvent(String summary) {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("repoChanges", List.of(Map.of("repository", "Naofel-eal/front-test")));
        return new AgentEvent(
            "event-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            AgentEventType.COMPLETED,
            Instant.now(),
            "provider:1",
            summary,
            null,
            null,
            null,
            null,
            null,
            artifacts,
            Map.of()
        );
    }

    private static final class RecordingCodeHostPort implements CodeHostPort {
        private PublishCodeChangesCommand publishCommand;
        private List<String> configuredRepositories = List.of();
        private List<CodeChangeRef> publishResult = List.of();

        @Override
        public List<CodeChangeRef> publish(PublishCodeChangesCommand command) {
            this.publishCommand = command;
            return publishResult;
        }

        @Override
        public List<String> configuredRepositories() {
            return configuredRepositories;
        }

        @Override
        public io.nud.domain.model.workspace.PreparedWorkspace prepareWorkspace(io.nud.application.command.workspace.PrepareWorkspaceCommand command) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingTicketingPort implements TicketingPort {
        private TransitionWorkItemCommand transition;
        private CommentWorkItemCommand comment;
        private WorkItem workItem;
        private boolean failLoad;
        private boolean failTransition;
        private boolean failComment;

        @Override
        public void comment(CommentWorkItemCommand command) {
            if (failComment) {
                throw new IllegalStateException("comment unavailable");
            }
            this.comment = command;
        }

        @Override
        public void transition(TransitionWorkItemCommand command) {
            if (failTransition) {
                throw new IllegalStateException("transition unavailable");
            }
            this.transition = command;
        }

        @Override
        public Optional<WorkItem> loadWorkItem(String workItemSystem, String workItemKey) {
            if (failLoad) {
                throw new IllegalStateException("load unavailable");
            }
            return Optional.ofNullable(workItem);
        }
    }
}
