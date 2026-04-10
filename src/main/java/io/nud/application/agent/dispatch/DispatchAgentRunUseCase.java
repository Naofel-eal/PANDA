package io.nud.application.agent.dispatch;

import io.nud.application.agent.port.AgentRuntimePort;
import io.nud.application.codehost.port.CodeHostPort;
import io.nud.application.command.agent.StartAgentRunCommand;
import io.nud.application.command.workspace.PrepareWorkspaceCommand;
import io.nud.application.service.WorkspaceLayoutService;
import io.nud.application.workflow.port.WorkflowHolder;
import io.nud.domain.model.codehost.CodeChangeRef;
import io.nud.domain.model.ticketing.WorkItem;
import io.nud.domain.model.workflow.Workflow;
import io.nud.domain.model.workspace.PreparedWorkspace;
import io.nud.domain.model.workspace.RepositoryWorkspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DispatchAgentRunUseCase {

    private static final Logger LOG = Logger.getLogger(DispatchAgentRunUseCase.class);

    @Inject
    AgentRuntimePort agentRuntimePort;

    @Inject
    CodeHostPort codeHostPort;

    @Inject
    WorkspaceLayoutService workspaceLayoutService;

    @Inject
    WorkflowHolder workflowHolder;

    public void execute(Workflow workflow, String objective, Map<String, Object> contextData) {
        workspaceLayoutService.ensureDirectories(workflow.workflowId());

        Map<String, Object> snapshot = new LinkedHashMap<>(contextData == null ? Map.of() : contextData);
        snapshot.put("phase", workflow.phase().name());

        List<String> repositories = extractRepositories(snapshot);
        Map<String, String> preferredBranches = resolvePreferredBranches(workflow, snapshot);

        PreparedWorkspace preparedWorkspace = codeHostPort.prepareWorkspace(
            new PrepareWorkspaceCommand(workflow.workflowId(), repositories, preferredBranches)
        );

        Map<String, Object> workspace = new LinkedHashMap<>(workspaceLayoutService.describe(workflow.workflowId()));
        workspace.put("projectRoot", preparedWorkspace.projectRoot());
        workspace.put("repositories", preparedWorkspace.repositories().stream()
            .map(DispatchAgentRunUseCase::toWorkspaceEntry)
            .toList());
        snapshot.put("workspace", workspace);
        snapshot.put("repositories", preparedWorkspace.repositories().stream()
            .map(RepositoryWorkspace::repository)
            .toList());

        StartAgentRunCommand command = StartAgentRunCommand.start(
            UUID.randomUUID(),
            workflow.workflowId(),
            workflow.agentRunId(),
            workflow.phase(),
            objective,
            snapshot
        );

        LOG.infof(
            "Dispatching agent run %s for ticket %s, phase %s",
            workflow.agentRunId(), workflow.ticketKey(), workflow.phase()
        );
        agentRuntimePort.startRun(command);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRepositories(Map<String, Object> snapshot) {
        Object repositories = snapshot.get("repositories");
        if (repositories instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }

        Object workItem = snapshot.get("workItem");
        if (workItem instanceof Map<?, ?> map) {
            Object workItemRepositories = map.get("repositories");
            if (workItemRepositories instanceof List<?> list && !list.isEmpty()) {
                return list.stream().map(String::valueOf).toList();
            }
        }
        if (workItem instanceof WorkItem item && !item.repositories().isEmpty()) {
            return item.repositories();
        }

        return codeHostPort.configuredRepositories();
    }

    private Map<String, String> resolvePreferredBranches(Workflow workflow, Map<String, Object> snapshot) {
        Map<String, String> branches = new LinkedHashMap<>();

        Object codeChange = snapshot.get("codeChange");
        if (codeChange instanceof CodeChangeRef ref) {
            if (ref.repository() != null && ref.sourceBranch() != null
                && !ref.repository().isBlank() && !ref.sourceBranch().isBlank()) {
                branches.put(ref.repository(), ref.sourceBranch());
            }
        } else if (codeChange instanceof Map<?, ?> rawMap) {
            Object repository = rawMap.get("repository");
            Object sourceBranch = rawMap.get("sourceBranch");
            if (repository instanceof String repositorySlug && !repositorySlug.isBlank()
                && sourceBranch instanceof String branch && !branch.isBlank()) {
                branches.put(repositorySlug, branch);
            }
        }

        for (CodeChangeRef pr : workflow.publishedPRs()) {
            if (pr.repository() != null && pr.sourceBranch() != null
                && !pr.repository().isBlank() && !pr.sourceBranch().isBlank()) {
                branches.putIfAbsent(pr.repository(), pr.sourceBranch());
            }
        }

        return branches;
    }

    private static Map<String, Object> toWorkspaceEntry(RepositoryWorkspace workspace) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("repository", workspace.repository());
        entry.put("projectRoot", workspace.projectRoot());
        return entry;
    }
}
