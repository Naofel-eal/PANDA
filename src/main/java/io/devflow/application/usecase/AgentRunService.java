package io.devflow.application.usecase;

import io.devflow.application.command.agent.StartAgentRunCommand;
import io.devflow.application.command.workspace.PrepareWorkspaceCommand;
import io.devflow.application.port.codehost.CodeHostPort;
import io.devflow.application.port.persistence.AgentRunStore;
import io.devflow.application.port.persistence.ExternalReferenceStore;
import io.devflow.application.port.persistence.OutboxCommandStore;
import io.devflow.application.port.support.JsonCodec;
import io.devflow.application.service.WorkflowAuditService;
import io.devflow.application.service.WorkspaceLayoutService;
import io.devflow.domain.agent.AgentRunStatus;
import io.devflow.domain.codehost.CodeChangeStatus;
import io.devflow.domain.codehost.ExternalReferenceType;
import io.devflow.domain.workflow.WorkflowAuditType;
import io.devflow.domain.workflow.WorkflowPhase;
import io.devflow.domain.workflow.WorkflowStatus;
import io.devflow.domain.agent.AgentRun;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.codehost.ExternalReference;
import io.devflow.domain.messaging.OutboxCommand;
import io.devflow.domain.workspace.PreparedWorkspace;
import io.devflow.domain.workspace.RepositoryWorkspace;
import io.devflow.domain.workflow.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentRunService {

    private static final Logger LOG = Logger.getLogger(AgentRunService.class);

    @Inject
    AgentRunStore agentRunStore;

    @Inject
    OutboxCommandStore outboxCommandStore;

    @Inject
    ExternalReferenceStore externalReferenceStore;

    @Inject
    JsonCodec jsonCodec;

    @Inject
    WorkflowAuditService workflowAuditService;

    @Inject
    WorkspaceLayoutService workspaceLayoutService;

    @Inject
    CodeHostPort codeHostPort;

    @Transactional
    public Workflow enqueueStartRun(
        Workflow workflow,
        WorkflowPhase phase,
        String objective,
        Map<String, Object> inputSnapshot
    ) {
        List<AgentRunStatus> activeStatuses = List.of(AgentRunStatus.PENDING, AgentRunStatus.STARTING, AgentRunStatus.RUNNING);
        if (agentRunStore.findByWorkflowAndStatuses(workflow.id(), activeStatuses).isPresent()) {
            LOG.infof(
                "Skipping agent run enqueue for workflow %s and ticket %s because another run is already active",
                workflow.id(),
                workflow.workItemKey()
            );
            return workflow.moveTo(phase, WorkflowStatus.WAITING_SYSTEM, Instant.now());
        }

        LOG.infof(
            "Preparing agent run for workflow %s, ticket %s, phase %s",
            workflow.id(),
            workflow.workItemKey(),
            phase
        );
        workspaceLayoutService.ensureDirectories(workflow.id());
        workspaceLayoutService.logResolvedLayout(workflow.id());
        Map<String, Object> preparedSnapshot = prepareSnapshotWithWorkspace(workflow, inputSnapshot);
        preparedSnapshot.put("phase", phase.name());

        Instant now = Instant.now();
        AgentRun run = agentRunStore.save(
            AgentRun.schedule(
                UUID.randomUUID(),
                workflow.id(),
                phase,
                jsonCodec.toJson(preparedSnapshot),
                now
            )
        );

        StartAgentRunCommand command = StartAgentRunCommand.start(
            UUID.randomUUID(),
            workflow.id(),
            run.id(),
            phase,
            objective,
            preparedSnapshot
        );

        outboxCommandStore.save(
            OutboxCommand.enqueue(
                command.commandId(),
                workflow.id(),
                run.id(),
                command.type(),
                jsonCodec.toJson(command),
                now
            )
        );

        workflowAuditService.record(workflow.id(), WorkflowAuditType.AGENT_RUN_ENQUEUED, command, workflow.workItemSystem(), null);
        LOG.infof(
            "Enqueued agent run %s for workflow %s, ticket %s, phase %s",
            run.id(),
            workflow.id(),
            workflow.workItemKey(),
            phase
        );
        return workflow.moveTo(phase, WorkflowStatus.WAITING_SYSTEM, now);
    }

    @Transactional
    public void enqueueCancelRun(Workflow workflow, AgentRun run) {
        Instant now = Instant.now();
        LOG.infof(
            "Enqueuing cancellation for agent run %s on workflow %s, ticket %s",
            run.id(),
            workflow.id(),
            workflow.workItemKey()
        );
        StartAgentRunCommand command = new StartAgentRunCommand(
            UUID.randomUUID(),
            workflow.id(),
            run.id(),
            io.devflow.domain.agent.AgentCommandType.CANCEL_RUN,
            run.phase(),
            "Cancel run " + run.id(),
            Map.of()
        );

        outboxCommandStore.save(
            OutboxCommand.enqueue(
                command.commandId(),
                workflow.id(),
                run.id(),
                command.type(),
                jsonCodec.toJson(command),
                now
            )
        );

        workflowAuditService.record(workflow.id(), WorkflowAuditType.AGENT_RUN_CANCEL_ENQUEUED, command, workflow.workItemSystem(), null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareSnapshotWithWorkspace(Workflow workflow, Map<String, Object> inputSnapshot) {
        Map<String, Object> snapshot = new LinkedHashMap<>(inputSnapshot == null ? Map.of() : inputSnapshot);
        PreparedWorkspace preparedWorkspace = codeHostPort.prepareWorkspace(
            new PrepareWorkspaceCommand(workflow.id(), extractRepositories(snapshot), resolvePreferredBranches(workflow, snapshot))
        );

        Map<String, Object> workspace = new LinkedHashMap<>(workspaceLayoutService.describe(workflow.id()));
        workspace.put("projectRoot", preparedWorkspace.projectRoot());
        workspace.put("repositories", preparedWorkspace.repositories().stream()
            .map(this::toWorkspaceEntry)
            .toList());
        snapshot.put("workspace", workspace);
        List<String> repositories = preparedWorkspace.repositories().stream()
            .map(RepositoryWorkspace::repository)
            .toList();
        snapshot.put("repositories", repositories);
        return snapshot;
    }

    private Map<String, Object> toWorkspaceEntry(RepositoryWorkspace workspace) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("repository", workspace.repository());
        entry.put("projectRoot", workspace.projectRoot());
        return entry;
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

        Object workflowContext = snapshot.get("workflowContext");
        if (workflowContext instanceof Map<?, ?> map) {
            Object contextRepositories = map.get("repositories");
            if (contextRepositories instanceof List<?> list && !list.isEmpty()) {
                return list.stream().map(String::valueOf).toList();
            }
            Object currentWorkItem = map.get("workItem");
            if (currentWorkItem instanceof Map<?, ?> workItemMap) {
                Object workItemRepositories = workItemMap.get("repositories");
                if (workItemRepositories instanceof List<?> list && !list.isEmpty()) {
                    return list.stream().map(String::valueOf).toList();
                }
            }
        }

        return List.of();
    }

    @SuppressWarnings("unchecked")
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

        for (ExternalReference reference : externalReferenceStore.findByWorkflow(workflow.id())) {
            if (reference.refType() != ExternalReferenceType.CODE_CHANGE) {
                continue;
            }
            Map<String, Object> metadata = jsonCodec.toMap(reference.metadataJson());
            Object status = metadata.get("status");
            Object repository = metadata.get("repository");
            Object sourceBranch = metadata.get("sourceBranch");
            if (!CodeChangeStatus.OPEN.name().equals(status) || !(repository instanceof String repositorySlug) || !(sourceBranch instanceof String branch)) {
                continue;
            }
            if (!repositorySlug.isBlank() && !branch.isBlank()) {
                branches.putIfAbsent(repositorySlug, branch);
            }
        }

        return branches;
    }
}
