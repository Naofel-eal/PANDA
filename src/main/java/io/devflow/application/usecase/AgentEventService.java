package io.devflow.application.usecase;

import io.devflow.application.command.agent.ReceiveAgentEventCommand;
import io.devflow.application.command.agent.StartAgentRunCommand;
import io.devflow.application.command.codehost.PublishCodeChangesCommand;
import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.command.workspace.PrepareWorkspaceCommand;
import io.devflow.application.port.agent.AgentRuntimePort;
import io.devflow.application.port.codehost.CodeHostPort;
import io.devflow.application.port.ticketing.TicketingPort;
import io.devflow.application.runtime.DevFlowRuntime;
import io.devflow.application.runtime.DevFlowRuntime.RunContext;
import io.devflow.application.service.WorkItemTransitionService;
import io.devflow.application.service.WorkspaceLayoutService;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.ticketing.IncomingComment;
import io.devflow.domain.ticketing.WorkItem;
import io.devflow.domain.ticketing.WorkItemTransitionTarget;
import io.devflow.domain.workflow.BlockerType;
import io.devflow.domain.workflow.RequestedFrom;
import io.devflow.domain.workflow.ResumeTrigger;
import io.devflow.domain.workflow.WorkflowPhase;
import io.devflow.domain.workspace.PreparedWorkspace;
import io.devflow.domain.workspace.RepositoryWorkspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentEventService {

    private static final Logger LOG = Logger.getLogger(AgentEventService.class);

    @Inject
    DevFlowRuntime runtime;

    @Inject
    TicketingPort ticketingPort;

    @Inject
    CodeHostPort codeHostPort;

    @Inject
    AgentRuntimePort agentRuntimePort;

    @Inject
    WorkspaceLayoutService workspaceLayoutService;

    @Inject
    WorkItemTransitionService workItemTransitionService;

    public boolean handle(ReceiveAgentEventCommand command) {
        RunContext run = runtime.current();
        if (run == null) {
            LOG.warnf("Received agent event %s but no active run context; ignoring", command.eventId());
            return false;
        }
        if (!run.agentRunId().equals(command.agentRunId())) {
            LOG.warnf(
                "Received agent event %s for agentRunId %s but current run is %s; ignoring",
                command.eventId(), command.agentRunId(), run.agentRunId()
            );
            return false;
        }

        LOG.infof(
            "Received agent event %s of type %s for ticket %s in phase %s",
            command.eventId(), command.type(), run.ticketKey(), run.phase()
        );

        switch (command.type()) {
            case RUN_STARTED -> handleRunStarted(run, command);
            case PROGRESS_REPORTED -> handleProgress(run, command);
            case INPUT_REQUIRED -> handleInputRequired(run, command);
            case COMPLETED -> handleCompleted(run, command);
            case FAILED -> handleFailed(run, command);
            case CANCELLED -> handleCancelled(run, command);
        }
        return true;
    }

    private void handleRunStarted(RunContext run, ReceiveAgentEventCommand command) {
        LOG.infof("Agent run %s started for ticket %s in phase %s", run.agentRunId(), run.ticketKey(), run.phase());
        moveTicket(run, WorkItemTransitionTarget.IN_PROGRESS, "AGENT_RUN_STARTED");
    }

    private void handleProgress(RunContext run, ReceiveAgentEventCommand command) {
        LOG.infof("Agent run %s progress reported for ticket %s", run.agentRunId(), run.ticketKey());
    }

    private void handleInputRequired(RunContext run, ReceiveAgentEventCommand command) {
        InputRequiredPayload input = normalizeInputRequired(command, run.phase());
        LOG.infof(
            "Agent run %s requires external input for ticket %s: %s",
            run.agentRunId(), run.ticketKey(), input.reasonCode()
        );

        moveTicket(run, WorkItemTransitionTarget.BLOCKED, input.reasonCode());
        if (input.suggestedComment() != null && !input.suggestedComment().isBlank()) {
            postTicketComment(run, input.suggestedComment(), input.reasonCode());
        }
        runtime.clearRun();
    }

    private void handleCompleted(RunContext run, ReceiveAgentEventCommand command) {
        LOG.infof("Agent run %s completed for ticket %s in phase %s", run.agentRunId(), run.ticketKey(), run.phase());

        if (run.phase() == WorkflowPhase.INFORMATION_COLLECTION) {
            chainToImplementation(run, command);
        } else if (run.phase() == WorkflowPhase.IMPLEMENTATION) {
            publishAndMoveToReview(run, command);
        } else {
            runtime.clearRun();
        }
    }

    private void handleFailed(RunContext run, ReceiveAgentEventCommand command) {
        String failureReasonCode = normalizeFailureReasonCode(command.reasonCode());
        LOG.warnf("Agent run %s failed for ticket %s: %s", run.agentRunId(), run.ticketKey(), failureReasonCode);
        moveTicket(run, WorkItemTransitionTarget.BLOCKED, failureReasonCode);
        postTicketComment(run, buildFailureComment(command), failureReasonCode);
        runtime.clearRun();
    }

    private void handleCancelled(RunContext run, ReceiveAgentEventCommand command) {
        LOG.infof("Agent run %s cancelled for ticket %s", run.agentRunId(), run.ticketKey());
        runtime.clearRun();
    }

    private static final int CHAIN_DISPATCH_DELAY_MS = 3_000;

    private void chainToImplementation(RunContext run, ReceiveAgentEventCommand command) {
        LOG.infof("Ticket %s completed info collection, chaining to implementation", run.ticketKey());
        UUID newAgentRunId = UUID.randomUUID();
        RunContext newRun = runtime.replacePhase(WorkflowPhase.IMPLEMENTATION, newAgentRunId);

        Map<String, Object> snapshot = buildChainSnapshot(newRun, command);
        String objective = "Implement work item " + run.ticketKey();

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(CHAIN_DISPATCH_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.errorf("Chain dispatch interrupted for ticket %s", run.ticketKey());
                handleChainDispatchFailure(run,
                    new RuntimeException("Interrupted while waiting to dispatch implementation", e));
                return;
            }
            try {
                dispatchAgentRun(newRun, objective, snapshot);
            } catch (RuntimeException e) {
                LOG.errorf(e, "Failed to dispatch implementation phase for ticket %s", run.ticketKey());
                handleChainDispatchFailure(run, e);
            }
        });
    }

    private void handleChainDispatchFailure(RunContext run, RuntimeException cause) {
        runtime.clearRun();
        moveTicket(run, WorkItemTransitionTarget.BLOCKED, "CHAIN_DISPATCH_FAILED");
        postTicketComment(
            run,
            "Devflow completed information collection but failed to start the implementation phase "
                + "due to a dispatch error: " + cause.getMessage()
                + "\n\nDevflow marked this ticket as blocked until the issue is resolved.",
            "CHAIN_DISPATCH_FAILED"
        );
    }

    private void publishAndMoveToReview(RunContext run, ReceiveAgentEventCommand command) {
        try {
            List<CodeChangeRef> codeChanges = codeHostPort.publish(buildPublishCommand(run, command));
            LOG.infof("Published %d pull request(s) for ticket %s", codeChanges.size(), run.ticketKey());
            for (CodeChangeRef codeChange : codeChanges) {
                runtime.addPublishedPR(codeChange);
            }
            moveTicket(run, WorkItemTransitionTarget.TO_REVIEW, "IMPLEMENTATION_COMPLETED");
            postTicketComment(
                run,
                buildTechnicalValidationComment(command.summary(), codeChanges),
                "IMPLEMENTATION_COMPLETED"
            );
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Failed to publish code changes for ticket %s", run.ticketKey());
            moveTicket(run, WorkItemTransitionTarget.BLOCKED, "PUBLISH_FAILED");
            postTicketComment(run, "Failed to publish pull requests: " + exception.getMessage(), "PUBLISH_FAILED");
        }
        runtime.clearRun();
    }

    private void dispatchAgentRun(RunContext run, String objective, Map<String, Object> snapshot) {
        workspaceLayoutService.ensureDirectories(run.workflowId());

        Map<String, Object> preparedSnapshot = prepareSnapshotWithWorkspace(run, snapshot);
        preparedSnapshot.put("phase", run.phase().name());

        StartAgentRunCommand command = StartAgentRunCommand.start(
            UUID.randomUUID(),
            run.workflowId(),
            run.agentRunId(),
            run.phase(),
            objective,
            preparedSnapshot
        );

        LOG.infof(
            "Dispatching agent run %s for ticket %s, phase %s",
            run.agentRunId(), run.ticketKey(), run.phase()
        );
        agentRuntimePort.startRun(command);
    }

    private Map<String, Object> buildChainSnapshot(RunContext run, ReceiveAgentEventCommand command) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowId", run.workflowId().toString());
        snapshot.put("workItemSystem", run.ticketSystem());
        snapshot.put("workItemKey", run.ticketKey());
        snapshot.put("previousRunSummary", command.summary() == null ? "" : command.summary());

        WorkItem workItem = safeLoadWorkItem(run);
        if (workItem != null) {
            snapshot.put("workItem", workItem);
        }
        List<IncomingComment> comments = safeLoadComments(run);
        if (!comments.isEmpty()) {
            snapshot.put("workItemComments", comments);
        }

        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareSnapshotWithWorkspace(RunContext run, Map<String, Object> inputSnapshot) {
        Map<String, Object> snapshot = new LinkedHashMap<>(inputSnapshot == null ? Map.of() : inputSnapshot);
        List<String> repositories = extractRepositories(snapshot);

        Map<String, String> preferredBranches = resolvePreferredBranches(run, snapshot);
        PreparedWorkspace preparedWorkspace = codeHostPort.prepareWorkspace(
            new PrepareWorkspaceCommand(run.workflowId(), repositories, preferredBranches)
        );

        Map<String, Object> workspace = new LinkedHashMap<>(workspaceLayoutService.describe(run.workflowId()));
        workspace.put("projectRoot", preparedWorkspace.projectRoot());
        workspace.put("repositories", preparedWorkspace.repositories().stream()
            .map(this::toWorkspaceEntry)
            .toList());
        snapshot.put("workspace", workspace);
        snapshot.put("repositories", preparedWorkspace.repositories().stream()
            .map(RepositoryWorkspace::repository)
            .toList());
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
        if (workItem instanceof WorkItem item && !item.repositories().isEmpty()) {
            return item.repositories();
        }

        return codeHostPort.configuredRepositories();
    }

    private Map<String, String> resolvePreferredBranches(RunContext run, Map<String, Object> snapshot) {
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

        for (CodeChangeRef pr : run.publishedPRs()) {
            if (pr.repository() != null && pr.sourceBranch() != null
                && !pr.repository().isBlank() && !pr.sourceBranch().isBlank()) {
                branches.putIfAbsent(pr.repository(), pr.sourceBranch());
            }
        }

        return branches;
    }

    private PublishCodeChangesCommand buildPublishCommand(RunContext run, ReceiveAgentEventCommand command) {
        Map<String, Object> artifacts = command.artifacts() == null ? Map.of() : command.artifacts();
        WorkItem workItem = safeLoadWorkItem(run);
        String title = workItem != null && workItem.title() != null ? workItem.title() : run.ticketKey();
        List<String> repositories = extractRepoChangesRepositories(artifacts);
        if (repositories.isEmpty()) {
            repositories = codeHostPort.configuredRepositories();
        }
        return new PublishCodeChangesCommand(
            run.workflowId(),
            run.ticketKey(),
            title,
            repositories,
            command.summary(),
            artifacts
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRepoChangesRepositories(Map<String, Object> artifacts) {
        Object repoChanges = artifacts.get("repoChanges");
        if (repoChanges instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                .filter(Map.class::isInstance)
                .map(entry -> ((Map<String, Object>) entry).get("repository"))
                .filter(repo -> repo instanceof String s && !s.isBlank())
                .map(String::valueOf)
                .toList();
        }
        return List.of();
    }

    private void postTicketComment(RunContext run, String comment, String reasonCode) {
        try {
            ticketingPort.comment(new CommentWorkItemCommand(run.ticketSystem(), run.ticketKey(), comment, reasonCode));
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Failed to post comment to ticket %s", run.ticketKey());
        }
    }

    private void moveTicket(RunContext run, WorkItemTransitionTarget target, String reasonCode) {
        workItemTransitionService.transitionDirect(run.ticketSystem(), run.ticketKey(), target, reasonCode);
    }

    private WorkItem safeLoadWorkItem(RunContext run) {
        try {
            return ticketingPort.loadWorkItem(run.ticketSystem(), run.ticketKey()).orElse(null);
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Unable to load work item %s during agent event handling", run.ticketKey());
            return null;
        }
    }

    private List<IncomingComment> safeLoadComments(RunContext run) {
        try {
            return ticketingPort.listComments(run.ticketSystem(), run.ticketKey());
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Unable to load comments for ticket %s during agent event handling", run.ticketKey());
            return List.of();
        }
    }

    private InputRequiredPayload normalizeInputRequired(ReceiveAgentEventCommand command, WorkflowPhase phase) {
        BlockerType blockerType = Objects.requireNonNullElse(command.blockerType(), BlockerType.MISSING_TICKET_INFORMATION);
        Map<String, Object> details = sanitizeInputRequiredDetails(command.details());
        List<String> missingInformation = extractMissingInformation(details);
        RequestedFrom requestedFrom = Objects.requireNonNullElse(command.requestedFrom(), defaultRequestedFrom(blockerType, phase));
        return new InputRequiredPayload(
            blockerType,
            normalizeReasonCode(command.reasonCode(), blockerType),
            normalizeSummary(command.summary(), missingInformation),
            normalizeSuggestedComment(command.suggestedComment(), missingInformation),
            requestedFrom,
            Objects.requireNonNullElse(command.resumeTrigger(), defaultResumeTrigger(blockerType, phase, requestedFrom))
        );
    }

    private Map<String, Object> sanitizeInputRequiredDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>(details);
        Object missingInformation = sanitized.get("missingInformation");
        if (missingInformation instanceof List<?> values) {
            List<String> cleaned = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(this::normalizeText)
                .filter(Objects::nonNull)
                .limit(2)
                .toList();
            if (cleaned.isEmpty()) {
                sanitized.remove("missingInformation");
            } else {
                sanitized.put("missingInformation", cleaned);
            }
        }
        return sanitized;
    }

    private List<String> extractMissingInformation(Map<String, Object> details) {
        Object missingInformation = details.get("missingInformation");
        if (missingInformation instanceof List<?> values) {
            return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        }
        return List.of();
    }

    private RequestedFrom defaultRequestedFrom(BlockerType blockerType, WorkflowPhase phase) {
        return switch (blockerType) {
            case MISSING_REPOSITORY_MAPPING, NO_EXECUTION_ENVIRONMENT -> RequestedFrom.SYSTEM;
            case MISSING_TECHNICAL_FEEDBACK_CONTEXT, WAITING_TECHNICAL_REVIEW -> RequestedFrom.DEV;
            case WAITING_BUSINESS_FEEDBACK -> RequestedFrom.BUSINESS;
            default -> phase == WorkflowPhase.TECHNICAL_VALIDATION ? RequestedFrom.DEV : RequestedFrom.BUSINESS;
        };
    }

    private ResumeTrigger defaultResumeTrigger(BlockerType blockerType, WorkflowPhase phase, RequestedFrom requestedFrom) {
        if (requestedFrom == RequestedFrom.SYSTEM) {
            return ResumeTrigger.MANUAL_RESUME_REQUESTED;
        }
        if (phase == WorkflowPhase.TECHNICAL_VALIDATION
            || blockerType == BlockerType.MISSING_TECHNICAL_FEEDBACK_CONTEXT
            || blockerType == BlockerType.WAITING_TECHNICAL_REVIEW) {
            return ResumeTrigger.CODE_CHANGE_REVIEW_COMMENT_RECEIVED;
        }
        if (phase == WorkflowPhase.BUSINESS_VALIDATION || blockerType == BlockerType.WAITING_BUSINESS_FEEDBACK) {
            return ResumeTrigger.BUSINESS_VALIDATION_REPORTED;
        }
        return ResumeTrigger.WORK_ITEM_COMMENT_RECEIVED;
    }

    private String normalizeReasonCode(String reasonCode, BlockerType blockerType) {
        String normalized = normalizeText(reasonCode);
        if (normalized == null) {
            return defaultReasonCode(blockerType);
        }
        String machineReadable = normalized
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return machineReadable.isBlank() ? defaultReasonCode(blockerType) : machineReadable;
    }

    private String defaultReasonCode(BlockerType blockerType) {
        return switch (blockerType) {
            case MISSING_REPOSITORY_MAPPING -> "NO_REPOSITORIES_FOUND";
            case NO_EXECUTION_ENVIRONMENT -> "NO_EXECUTION_ENVIRONMENT";
            case MISSING_TECHNICAL_FEEDBACK_CONTEXT -> "MISSING_TECHNICAL_FEEDBACK_CONTEXT";
            case WAITING_TECHNICAL_REVIEW -> "WAITING_TECHNICAL_REVIEW";
            case WAITING_BUSINESS_FEEDBACK -> "WAITING_BUSINESS_FEEDBACK";
            case NOT_ELIGIBLE -> "NOT_ELIGIBLE";
            case MISSING_TICKET_INFORMATION -> "TASK_CONTEXT_UNCLEAR";
        };
    }

    private String normalizeSummary(String summary, List<String> missingInformation) {
        String normalized = normalizeText(summary);
        if (normalized != null) {
            return normalized;
        }
        if (!missingInformation.isEmpty()) {
            return truncate("Missing information: " + missingInformation.get(0), 280);
        }
        return "Additional clarification is required before the agent can continue.";
    }

    private String normalizeSuggestedComment(String suggestedComment, List<String> missingInformation) {
        String normalized = normalizeText(suggestedComment);
        if (normalized != null) {
            return normalized;
        }
        if (!missingInformation.isEmpty()) {
            return truncate("Clarification needed before continuing: " + missingInformation.get(0), 320);
        }
        return "Clarification is needed before the agent can continue this ticket.";
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3).trim() + "...";
    }

    private String normalizeFailureReasonCode(String reasonCode) {
        String normalized = normalizeText(reasonCode);
        if (normalized == null) {
            return "AGENT_RUN_FAILED";
        }
        String machineReadable = normalized
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return machineReadable.isBlank() ? "AGENT_RUN_FAILED" : machineReadable;
    }

    private String buildFailureComment(ReceiveAgentEventCommand command) {
        String summary = normalizeText(command.summary());
        String detail = extractFailureDetail(command.details());
        StringBuilder comment = new StringBuilder("Implementation failed.");
        if (summary != null) {
            comment.append("\n\n").append(summary);
        }
        if (detail != null && (summary == null || !detail.equals(summary))) {
            comment.append("\n\nDetail: ").append(detail);
        }
        comment.append("\n\nDevflow marked this ticket as blocked until the issue is addressed or the run is resumed.");
        return comment.toString();
    }

    private String extractFailureDetail(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        Object error = details.get("error");
        if (error instanceof String text) {
            return normalizeText(text);
        }
        Object solution = details.get("solution");
        if (solution instanceof String text) {
            return normalizeText(text);
        }
        return null;
    }

    private String buildTechnicalValidationComment(String summary, List<CodeChangeRef> codeChanges) {
        StringBuilder comment = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            comment.append(summary.trim());
        } else {
            comment.append("Implementation completed and ready for technical validation.");
        }

        if (!codeChanges.isEmpty()) {
            comment.append("\n\n");
            comment.append(codeChanges.size() == 1 ? "Pull request created:" : "Pull requests created:");
            for (CodeChangeRef codeChange : codeChanges) {
                comment.append("\n- ");
                comment.append(codeChange.repository());
                if (codeChange.url() != null && !codeChange.url().isBlank()) {
                    comment.append(": ").append(codeChange.url());
                }
            }
        }
        return comment.toString();
    }

    private record InputRequiredPayload(
        BlockerType blockerType,
        String reasonCode,
        String summary,
        String suggestedComment,
        RequestedFrom requestedFrom,
        ResumeTrigger resumeTrigger
    ) {
    }
}
