package io.devflow.application.workflow.cancel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.devflow.application.agent.port.AgentRuntimePort;
import io.devflow.application.command.agent.CancelAgentRunCommand;
import io.devflow.application.workflow.InMemoryWorkflowHolder;
import io.devflow.domain.model.workflow.Workflow;
import io.devflow.domain.model.workflow.WorkflowPhase;
import io.devflow.support.ReflectionTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CancelStaleRunUseCaseTest {

    @Test
    @DisplayName("Given an active fresh run when stale cancellation is evaluated then the workflow keeps running")
    void givenActiveFreshRun_whenStaleCancellationIsEvaluated_thenWorkflowKeepsRunning() {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        Workflow workflow = Workflow.start(UUID.randomUUID(), UUID.randomUUID(), "jira", "SCRUM-1", WorkflowPhase.IMPLEMENTATION, "Implement");
        holder.start(workflow);

        RecordingAgentRuntimePort runtimePort = new RecordingAgentRuntimePort();
        CancelStaleRunUseCase useCase = new CancelStaleRunUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "agentRuntimePort", runtimePort);

        useCase.execute(10);

        assertNull(runtimePort.cancelCommand);
        assertTrue(holder.isBusy());
    }

    @Test
    @DisplayName("Given an active stale run when cancellation is requested then the runtime is called and the local workflow is cleared")
    void givenActiveStaleRun_whenCancellationIsRequested_thenRuntimeIsCalledAndWorkflowIsCleared() throws Exception {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        Workflow workflow = Workflow.start(UUID.randomUUID(), UUID.randomUUID(), "jira", "SCRUM-2", WorkflowPhase.IMPLEMENTATION, "Implement");
        holder.start(workflow);
        Thread.sleep(5);

        RecordingAgentRuntimePort runtimePort = new RecordingAgentRuntimePort();
        CancelStaleRunUseCase useCase = new CancelStaleRunUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "agentRuntimePort", runtimePort);

        useCase.execute(0);

        assertEquals(workflow.agentRunId(), runtimePort.cancelCommand.agentRunId());
        assertNull(holder.current());
    }

    @Test
    @DisplayName("Given a stale run when runtime cancellation fails then the workflow is still cleared locally")
    void givenStaleRun_whenRuntimeCancellationFails_thenWorkflowIsStillClearedLocally() throws Exception {
        InMemoryWorkflowHolder holder = new InMemoryWorkflowHolder();
        Workflow workflow = Workflow.start(UUID.randomUUID(), UUID.randomUUID(), "jira", "SCRUM-3", WorkflowPhase.IMPLEMENTATION, "Implement");
        holder.start(workflow);
        Thread.sleep(5);

        CancelStaleRunUseCase useCase = new CancelStaleRunUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", holder);
        ReflectionTestSupport.setField(useCase, "agentRuntimePort", new AgentRuntimePort() {
            @Override
            public void startRun(io.devflow.application.command.agent.StartAgentRunCommand command) {
            }

            @Override
            public void cancelRun(CancelAgentRunCommand command) {
                throw new IllegalStateException("runtime unavailable");
            }
        });

        useCase.execute(0);

        assertNull(holder.current());
    }

    @Test
    @DisplayName("Given a stale flag without a current workflow when cancellation is evaluated then DevFlow does not call the runtime")
    void givenAStaleFlagWithoutACurrentWorkflow_whenCancellationIsEvaluated_thenDevFlowDoesNotCallTheRuntime() {
        RecordingAgentRuntimePort runtimePort = new RecordingAgentRuntimePort();
        CancelStaleRunUseCase useCase = new CancelStaleRunUseCase();
        ReflectionTestSupport.setField(useCase, "workflowHolder", new io.devflow.application.workflow.port.WorkflowHolder() {
            @Override
            public boolean isBusy() {
                return false;
            }

            @Override
            public boolean isStale(java.time.Duration maxDuration) {
                return true;
            }

            @Override
            public Workflow current() {
                return null;
            }

            @Override
            public Workflow start(Workflow workflow) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void clear() {
            }

            @Override
            public void clearIfMatches(UUID agentRunId) {
            }

            @Override
            public Workflow replacePhase(WorkflowPhase newPhase, UUID newAgentRunId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void addPublishedPR(io.devflow.domain.model.codehost.CodeChangeRef codeChange) {
            }
        });
        ReflectionTestSupport.setField(useCase, "agentRuntimePort", runtimePort);

        useCase.execute(0);

        assertNull(runtimePort.cancelCommand);
    }

    private static final class RecordingAgentRuntimePort implements AgentRuntimePort {
        private CancelAgentRunCommand cancelCommand;

        @Override
        public void startRun(io.devflow.application.command.agent.StartAgentRunCommand command) {
        }

        @Override
        public void cancelRun(CancelAgentRunCommand command) {
            this.cancelCommand = command;
        }
    }
}
