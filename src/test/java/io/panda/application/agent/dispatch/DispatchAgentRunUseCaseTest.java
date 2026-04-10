package io.panda.application.agent.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.panda.application.agent.port.AgentRuntimePort;
import io.panda.application.codehost.port.CodeHostPort;
import io.panda.application.command.agent.CancelAgentRunCommand;
import io.panda.application.command.agent.StartAgentRunCommand;
import io.panda.application.command.codehost.PublishCodeChangesCommand;
import io.panda.application.command.workspace.PrepareWorkspaceCommand;
import io.panda.application.config.ApplicationConfig;
import io.panda.application.service.WorkspaceLayoutService;
import io.panda.domain.model.codehost.CodeChangeRef;
import io.panda.domain.model.ticketing.WorkItem;
import io.panda.domain.model.workflow.Workflow;
import io.panda.domain.model.workflow.WorkflowPhase;
import io.panda.domain.model.workspace.PreparedWorkspace;
import io.panda.domain.model.workspace.RepositoryWorkspace;
import io.panda.support.ReflectionTestSupport;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DispatchAgentRunUseCaseTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given an implementation run with explicit repositories when PANDA dispatches the agent then the workspace snapshot follows the active pull request context")
    void givenImplementationRunWithExplicitRepositories_whenPANDADispatchesTheAgent_thenTheWorkspaceSnapshotFollowsTheActivePullRequestContext() {
        RecordingAgentRuntimePort agentRuntimePort = new RecordingAgentRuntimePort();
        RecordingCodeHostPort codeHostPort = new RecordingCodeHostPort(
            List.of("acme/api", "acme/web"),
            new PreparedWorkspace(
                tempDir.resolve("prepared").toString(),
                List.of(
                    new RepositoryWorkspace("acme/api", "/workspace/acme-api"),
                    new RepositoryWorkspace("acme/web", "/workspace/acme-web")
                )
            )
        );
        DispatchAgentRunUseCase useCase = useCase(agentRuntimePort, codeHostPort);
        Workflow workflow = Workflow.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "jira",
            "SCRUM-1",
            WorkflowPhase.IMPLEMENTATION,
            "Implement SCRUM-1"
        );
        workflow.addPublishedPR(new CodeChangeRef("github", "2", "acme/web", "https://github.com/acme/web/pull/2", "carry-over", "main"));

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("repositories", List.of("acme/api", "acme/web"));
        context.put("codeChange", Map.of("repository", "acme/api", "sourceBranch", "review-fixes"));

        useCase.execute(workflow, "Implement SCRUM-1", context);

        assertIterableEquals(List.of("acme/api", "acme/web"), codeHostPort.prepareCommand.repositories());
        assertEquals(Map.of("acme/api", "review-fixes", "acme/web", "carry-over"), codeHostPort.prepareCommand.preferredBranches());
        assertEquals(WorkflowPhase.IMPLEMENTATION, agentRuntimePort.command.phase());
        assertEquals("Implement SCRUM-1", agentRuntimePort.command.objective());
        assertEquals("IMPLEMENTATION", agentRuntimePort.command.inputSnapshot().get("phase"));
        assertIterableEquals(List.of("acme/api", "acme/web"), repositories(agentRuntimePort.command.inputSnapshot()));
        assertEquals(tempDir.resolve("prepared").toString(), workspace(agentRuntimePort.command.inputSnapshot()).get("projectRoot"));
        assertNotNull(workspace(agentRuntimePort.command.inputSnapshot()).get("repositories"));
    }

    @Test
    @DisplayName("Given repository targets in a work item map when PANDA dispatches the agent then those repositories become the implementation scope")
    void givenRepositoryTargetsInAWorkItemMap_whenPANDADispatchesTheAgent_thenThoseRepositoriesBecomeTheImplementationScope() {
        RecordingCodeHostPort codeHostPort = new RecordingCodeHostPort(
            List.of("fallback/repo"),
            new PreparedWorkspace(tempDir.toString(), List.of(new RepositoryWorkspace("acme/mobile", "/workspace/mobile")))
        );
        DispatchAgentRunUseCase useCase = useCase(new RecordingAgentRuntimePort(), codeHostPort);
        Workflow workflow = Workflow.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "jira",
            "SCRUM-2",
            WorkflowPhase.IMPLEMENTATION,
            "Implement SCRUM-2"
        );

        useCase.execute(workflow, "Implement SCRUM-2", Map.of(
            "workItem", Map.of("repositories", List.of("acme/mobile")),
            "codeChange", new CodeChangeRef("github", "3", "acme/mobile", "url", "review-branch", "main")
        ));

        assertIterableEquals(List.of("acme/mobile"), codeHostPort.prepareCommand.repositories());
        assertEquals(Map.of("acme/mobile", "review-branch"), codeHostPort.prepareCommand.preferredBranches());
    }

    @Test
    @DisplayName("Given repository targets on the ticket itself when PANDA dispatches the agent then the ticket repositories are prepared")
    void givenRepositoryTargetsOnTheTicketItself_whenPANDADispatchesTheAgent_thenTheTicketRepositoriesArePrepared() {
        RecordingCodeHostPort codeHostPort = new RecordingCodeHostPort(
            List.of("fallback/repo"),
            new PreparedWorkspace(tempDir.toString(), List.of(new RepositoryWorkspace("acme/backoffice", "/workspace/backoffice")))
        );
        DispatchAgentRunUseCase useCase = useCase(new RecordingAgentRuntimePort(), codeHostPort);
        Workflow workflow = Workflow.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "jira",
            "SCRUM-3",
            WorkflowPhase.IMPLEMENTATION,
            "Implement SCRUM-3"
        );

        useCase.execute(workflow, "Implement SCRUM-3", Map.of(
            "workItem", new WorkItem(
                "SCRUM-3",
                "Story",
                "Backoffice change",
                "Details",
                "In Progress",
                "https://jira.example/browse/SCRUM-3",
                List.of("ops"),
                List.of("acme/backoffice"),
                null
            )
        ));

        assertIterableEquals(List.of("acme/backoffice"), codeHostPort.prepareCommand.repositories());
        assertEquals(Map.of(), codeHostPort.prepareCommand.preferredBranches());
    }

    @Test
    @DisplayName("Given no repository hint in the workflow context when PANDA dispatches the agent then configured repositories are used as the default scope")
    void givenNoRepositoryHintInTheWorkflowContext_whenPANDADispatchesTheAgent_thenConfiguredRepositoriesAreUsedAsTheDefaultScope() {
        RecordingCodeHostPort codeHostPort = new RecordingCodeHostPort(
            List.of("acme/api", "acme/web"),
            new PreparedWorkspace(
                tempDir.toString(),
                List.of(
                    new RepositoryWorkspace("acme/api", "/workspace/acme-api"),
                    new RepositoryWorkspace("acme/web", "/workspace/acme-web")
                )
            )
        );
        DispatchAgentRunUseCase useCase = useCase(new RecordingAgentRuntimePort(), codeHostPort);
        Workflow workflow = Workflow.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "jira",
            "SCRUM-4",
            WorkflowPhase.INFORMATION_COLLECTION,
            "Understand SCRUM-4"
        );

        useCase.execute(workflow, "Understand SCRUM-4", Map.of());

        assertIterableEquals(List.of("acme/api", "acme/web"), codeHostPort.prepareCommand.repositories());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> workspace(Map<String, Object> snapshot) {
        return (Map<String, Object>) snapshot.get("workspace");
    }

    @SuppressWarnings("unchecked")
    private List<String> repositories(Map<String, Object> snapshot) {
        return (List<String>) snapshot.get("repositories");
    }

    private DispatchAgentRunUseCase useCase(RecordingAgentRuntimePort agentRuntimePort, RecordingCodeHostPort codeHostPort) {
        DispatchAgentRunUseCase useCase = new DispatchAgentRunUseCase();
        ReflectionTestSupport.setField(useCase, "agentRuntimePort", agentRuntimePort);
        ReflectionTestSupport.setField(useCase, "codeHostPort", codeHostPort);

        WorkspaceLayoutService workspaceLayoutService = new WorkspaceLayoutService();
        ReflectionTestSupport.setField(workspaceLayoutService, "config", new ApplicationConfig() {
            @Override
            public Workspace workspace() {
                return () -> tempDir.toString();
            }
        });
        ReflectionTestSupport.setField(useCase, "workspaceLayoutService", workspaceLayoutService);
        return useCase;
    }

    private static final class RecordingAgentRuntimePort implements AgentRuntimePort {
        private StartAgentRunCommand command;

        @Override
        public void startRun(StartAgentRunCommand command) {
            this.command = command;
        }

        @Override
        public void cancelRun(CancelAgentRunCommand command) {
        }
    }

    private static final class RecordingCodeHostPort implements CodeHostPort {
        private final List<String> configuredRepositories;
        private final PreparedWorkspace preparedWorkspace;
        private PrepareWorkspaceCommand prepareCommand;

        private RecordingCodeHostPort(List<String> configuredRepositories, PreparedWorkspace preparedWorkspace) {
            this.configuredRepositories = configuredRepositories;
            this.preparedWorkspace = preparedWorkspace;
        }

        @Override
        public List<CodeChangeRef> publish(PublishCodeChangesCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> configuredRepositories() {
            return configuredRepositories;
        }

        @Override
        public PreparedWorkspace prepareWorkspace(PrepareWorkspaceCommand command) {
            this.prepareCommand = command;
            return preparedWorkspace;
        }
    }
}
