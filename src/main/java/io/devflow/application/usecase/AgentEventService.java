package io.devflow.application.usecase;

import io.devflow.application.command.agent.ReceiveAgentEventCommand;
import io.devflow.application.command.codehost.PublishCodeChangesCommand;
import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.port.codehost.CodeHostPort;
import io.devflow.application.port.persistence.AgentEventStore;
import io.devflow.application.port.persistence.AgentRunStore;
import io.devflow.application.port.persistence.ExternalReferenceStore;
import io.devflow.application.port.persistence.WorkflowBlockerStore;
import io.devflow.application.port.persistence.WorkflowStore;
import io.devflow.application.port.support.JsonCodec;
import io.devflow.application.port.ticketing.TicketingPort;
import io.devflow.application.service.WorkItemTransitionService;
import io.devflow.application.service.WorkflowAuditService;
import io.devflow.application.service.WorkspaceLayoutService;
import io.devflow.domain.workflow.BlockerType;
import io.devflow.domain.codehost.CodeChangeStatus;
import io.devflow.domain.codehost.ExternalReferenceType;
import io.devflow.domain.ticketing.WorkItemTransitionTarget;
import io.devflow.domain.workflow.RequestedFrom;
import io.devflow.domain.workflow.ResumeTrigger;
import io.devflow.domain.workflow.WorkflowAuditType;
import io.devflow.domain.workflow.WorkflowPhase;
import io.devflow.domain.workflow.WorkflowStatus;
import io.devflow.domain.agent.AgentEvent;
import io.devflow.domain.agent.AgentRun;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.codehost.ExternalReference;
import io.devflow.domain.workflow.Workflow;
import io.devflow.domain.workflow.WorkflowBlocker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentEventService {

    private static final Logger LOG = Logger.getLogger(AgentEventService.class);

    @Inject
    AgentEventStore agentEventStore;

    @Inject
    AgentRunStore agentRunStore;

    @Inject
    WorkflowStore workflowStore;

    @Inject
    WorkflowBlockerStore workflowBlockerStore;

    @Inject
    ExternalReferenceStore externalReferenceStore;

    @Inject
    WorkflowAuditService workflowAuditService;

    @Inject
    TicketingPort ticketingPort;

    @Inject
    AgentRunService agentRunService;

    @Inject
    WorkspaceLayoutService workspaceLayoutService;

    @Inject
    CodeHostPort codeHostPort;

    @Inject
    JsonCodec jsonCodec;

    @Inject
    WorkItemTransitionService workItemTransitionService;

    @Transactional
    public boolean handle(ReceiveAgentEventCommand command) {
        if (agentEventStore.findByEventId(command.eventId()).isPresent()) {
            LOG.infof("Ignoring duplicate agent event %s of type %s", command.eventId(), command.type());
            return false;
        }

        AgentRun run = agentRunStore.findRunById(command.agentRunId())
            .orElseThrow(() -> new NotFoundException("Unknown agentRunId " + command.agentRunId()));
        Workflow workflow = workflowStore.findById(command.workflowId())
            .orElseThrow(() -> new NotFoundException("Unknown workflowId " + command.workflowId()));
        if (!run.workflowId().equals(workflow.id())) {
            throw new IllegalArgumentException("agentRunId does not belong to workflowId");
        }

        AgentEvent event = agentEventStore.save(
            AgentEvent.receive(
                UUID.randomUUID(),
                run.id(),
                command.eventId(),
                command.type(),
                jsonCodec.toJson(command),
                Objects.requireNonNullElseGet(command.occurredAt(), Instant::now)
            )
        );
        LOG.infof(
            "Received agent event %s of type %s for workflow %s and run %s",
            command.eventId(),
            command.type(),
            command.workflowId(),
            command.agentRunId()
        );

        UpdatedState updatedState = switch (command.type()) {
            case RUN_STARTED -> applyRunStarted(run, workflow, command);
            case PROGRESS_REPORTED -> applyProgress(run, workflow, command);
            case INPUT_REQUIRED -> applyInputRequired(run, workflow, command);
            case COMPLETED -> applyCompleted(run, workflow, command);
            case FAILED -> applyFailed(run, workflow, command);
            case CANCELLED -> applyCancelled(run, workflow, command);
        };

        run = updatedState.run();
        workflow = updatedState.workflow().touch(Instant.now());
        event = event.markProcessed(Instant.now());
        agentEventStore.save(event);
        agentRunStore.save(run);
        workflowStore.save(workflow);
        return true;
    }

    private UpdatedState applyRunStarted(AgentRun run, Workflow workflow, ReceiveAgentEventCommand command) {
        AgentRun updatedRun = run.markRunning(Objects.requireNonNullElseGet(command.occurredAt(), Instant::now), command.providerRunRef());
        Workflow updatedWorkflow = workflow.markActive(Instant.now());
        LOG.infof("Agent run %s started for ticket %s in phase %s", run.id(), workflow.workItemKey(), run.phase());
        moveTicket(updatedWorkflow, WorkItemTransitionTarget.IN_PROGRESS, "AGENT_RUN_STARTED");
        workflowAuditService.record(workflow.id(), WorkflowAuditType.AGENT_RUN_STARTED, command, workflow.workItemSystem(), command.eventId());
        return new UpdatedState(updatedRun, updatedWorkflow);
    }

    private UpdatedState applyProgress(AgentRun run, Workflow workflow, ReceiveAgentEventCommand command) {
        workflowAuditService.record(workflow.id(), WorkflowAuditType.AGENT_PROGRESS_REPORTED, command, workflow.workItemSystem(), command.eventId());
        return new UpdatedState(run, workflow);
    }

    private UpdatedState applyInputRequired(AgentRun run, Workflow workflow, ReceiveAgentEventCommand command) {
        Instant now = Instant.now();
        InputRequiredPayload input = normalizeInputRequired(command, workflow.phase());
        AgentRun updatedRun = run.waitForInput(now);
        if (command.reasonCode() == null || command.reasonCode().isBlank() || command.suggestedComment() == null || command.suggestedComment().isBlank()) {
            LOG.warnf(
                "Agent run %s sent an incomplete INPUT_REQUIRED payload for ticket %s; synthesizing fallback blocker fields",
                run.id(),
                workflow.workItemKey()
            );
        }
        LOG.infof(
            "Agent run %s requires external input for ticket %s: %s",
            run.id(),
            workflow.workItemKey(),
            input.reasonCode()
        );

        WorkflowBlocker blocker = workflowBlockerStore.findOpenByWorkflowId(workflow.id())
            .orElseGet(() -> WorkflowBlocker.open(
                UUID.randomUUID(),
                workflow.id(),
                workflow.phase(),
                input.blockerType(),
                input.reasonCode(),
                input.summary(),
                input.suggestedComment(),
                input.requestedFrom(),
                input.resumeTrigger(),
                input.detailsJson(),
                now
            ));

        blocker = blocker.refresh(
            workflow.phase(),
            input.blockerType(),
            input.reasonCode(),
            input.summary(),
            input.suggestedComment(),
            input.requestedFrom(),
            input.resumeTrigger(),
            input.detailsJson()
        );
        blocker = workflowBlockerStore.save(blocker);

        Workflow updatedWorkflow = workflow.attachBlocker(blocker.id(), WorkflowStatus.WAITING_EXTERNAL_INPUT, now);
        workflowAuditService.record(workflow.id(), WorkflowAuditType.WORKFLOW_INPUT_REQUIRED, command, workflow.workItemSystem(), command.eventId());
        moveTicket(updatedWorkflow, WorkItemTransitionTarget.BLOCKED, input.reasonCode());

        if (blocker.suggestedComment() != null && !blocker.suggestedComment().isBlank()) {
            requestTicketComment(updatedWorkflow, blocker.suggestedComment(), blocker.reasonCode());
        }
        return new UpdatedState(updatedRun, updatedWorkflow);
    }

    private UpdatedState applyCompleted(AgentRun run, Workflow workflow, ReceiveAgentEventCommand command) {
        Instant now = Instant.now();
        AgentRun updatedRun = run.markCompleted(now);
        closeOpenBlocker(workflow.id());
        Workflow updatedWorkflow = workflow.clearBlocker(now);
        LOG.infof("Agent run %s completed for ticket %s in phase %s", run.id(), workflow.workItemKey(), run.phase());
        updatedRun = agentRunStore.save(updatedRun);

        if (command.artifacts() != null) {
            persistReferences(updatedWorkflow, command.artifacts());
        }

        if (run.phase() == WorkflowPhase.INFORMATION_COLLECTION) {
            LOG.infof("Ticket %s is ready for implementation after information collection", updatedWorkflow.workItemKey());
            updatedWorkflow = agentRunService.enqueueStartRun(
                updatedWorkflow,
                WorkflowPhase.IMPLEMENTATION,
                "Implement work item " + updatedWorkflow.workItemKey(),
                buildFollowUpSnapshot(updatedWorkflow, command)
            );
        } else if (run.phase() == WorkflowPhase.IMPLEMENTATION) {
            List<CodeChangeRef> codeChanges = codeHostPort.publish(buildPublishCommand(updatedWorkflow, command));
            LOG.infof(
                "Published %d pull request(s) for ticket %s",
                codeChanges.size(),
                updatedWorkflow.workItemKey()
            );
            for (CodeChangeRef codeChange : codeChanges) {
                registerCodeChangeReference(updatedWorkflow, codeChange, CodeChangeStatus.OPEN);
            }
            updatedWorkflow = updatedWorkflow.moveTo(WorkflowPhase.TECHNICAL_VALIDATION, WorkflowStatus.WAITING_SYSTEM, now);
            moveTicket(updatedWorkflow, WorkItemTransitionTarget.TO_REVIEW, "IMPLEMENTATION_COMPLETED");
            requestTicketComment(
                updatedWorkflow,
                buildTechnicalValidationComment(command.summary(), codeChanges),
                "IMPLEMENTATION_COMPLETED"
            );
        } else if (run.phase() == WorkflowPhase.TECHNICAL_VALIDATION) {
            updatedWorkflow = updatedWorkflow.moveTo(WorkflowPhase.TECHNICAL_VALIDATION, WorkflowStatus.WAITING_SYSTEM, now);
        } else if (run.phase() == WorkflowPhase.BUSINESS_VALIDATION) {
            updatedWorkflow = updatedWorkflow.moveTo(WorkflowPhase.TECHNICAL_VALIDATION, WorkflowStatus.WAITING_SYSTEM, now);
        }

        workflowAuditService.record(updatedWorkflow.id(), WorkflowAuditType.AGENT_RUN_COMPLETED, command, updatedWorkflow.workItemSystem(), command.eventId());
        return new UpdatedState(updatedRun, updatedWorkflow);
    }

    private UpdatedState applyFailed(AgentRun run, Workflow workflow, ReceiveAgentEventCommand command) {
        Instant now = Instant.now();
        AgentRun updatedRun = run.markFailed(now);
        Workflow updatedWorkflow = workflow.clearBlocker(now).markFailed(now);
        String failureReasonCode = normalizeFailureReasonCode(command.reasonCode());
        LOG.warnf("Agent run %s failed for ticket %s", run.id(), workflow.workItemKey());
        moveTicket(updatedWorkflow, WorkItemTransitionTarget.BLOCKED, failureReasonCode);
        requestTicketComment(updatedWorkflow, buildFailureComment(command), failureReasonCode);
        workflowAuditService.record(workflow.id(), WorkflowAuditType.AGENT_RUN_FAILED, command, workflow.workItemSystem(), command.eventId());
        return new UpdatedState(updatedRun, updatedWorkflow);
    }

    private UpdatedState applyCancelled(AgentRun run, Workflow workflow, ReceiveAgentEventCommand command) {
        Instant now = Instant.now();
        AgentRun updatedRun = run.markCancelled(now);
        Workflow updatedWorkflow = workflow;
        LOG.infof("Agent run %s cancelled for ticket %s", run.id(), workflow.workItemKey());
        if (updatedWorkflow.status() != WorkflowStatus.CANCELLED) {
            updatedWorkflow = updatedWorkflow.moveTo(updatedWorkflow.phase(), WorkflowStatus.WAITING_SYSTEM, now);
        }
        workflowAuditService.record(workflow.id(), WorkflowAuditType.AGENT_RUN_CANCELLED, command, workflow.workItemSystem(), command.eventId());
        return new UpdatedState(updatedRun, updatedWorkflow);
    }

    private void closeOpenBlocker(UUID workflowId) {
        workflowBlockerStore.findOpenByWorkflowId(workflowId).ifPresent(blocker -> {
            workflowBlockerStore.save(blocker.resolve(Instant.now()));
        });
    }

    @SuppressWarnings("unchecked")
    private void persistReferences(Workflow workflow, Map<String, Object> artifacts) {
        Object codeChanges = artifacts.get("codeChanges");
        if (!(codeChanges instanceof List<?> list)) {
            return;
        }

        for (Object current : list) {
            if (current instanceof Map<?, ?> map) {
                Object system = map.get("system");
                Object externalId = map.get("externalId");
                if (system instanceof String systemValue && externalId instanceof String externalIdValue) {
                    ExternalReference reference = externalReferenceStore
                        .findByReference(ExternalReferenceType.CODE_CHANGE, systemValue, externalIdValue)
                        .orElseGet(() -> ExternalReference.create(
                            UUID.randomUUID(),
                            workflow.id(),
                            ExternalReferenceType.CODE_CHANGE,
                            systemValue,
                            externalIdValue,
                            map.get("url") instanceof String url ? url : null,
                            jsonCodec.toJson(map),
                            Instant.now()
                        ));
                    reference = reference.update(map.get("url") instanceof String url ? url : reference.url(), jsonCodec.toJson(map));
                    externalReferenceStore.save(reference);
                }
            }
        }
    }

    private Map<String, Object> buildFollowUpSnapshot(Workflow workflow, ReceiveAgentEventCommand command) {
        return Map.ofEntries(
            Map.entry("workflowId", workflow.id().toString()),
            Map.entry("phase", WorkflowPhase.IMPLEMENTATION.name()),
            Map.entry("workItemSystem", workflow.workItemSystem()),
            Map.entry("workItemKey", workflow.workItemKey()),
            Map.entry("workspace", workspaceLayoutService.describe(workflow.id())),
            Map.entry("previousRunSummary", command.summary() == null ? "" : command.summary())
        );
    }

    private void requestTicketComment(Workflow workflow, String comment, String reasonCode) {
        workflowAuditService.record(
            workflow.id(),
            WorkflowAuditType.WORK_ITEM_COMMENT_REQUESTED,
            Map.of(
                "workItemSystem", workflow.workItemSystem(),
                "workItemKey", workflow.workItemKey(),
                "reasonCode", reasonCode == null ? "" : reasonCode,
                "comment", comment
            ),
            workflow.workItemSystem(),
            null
        );
        ticketingPort.comment(new CommentWorkItemCommand(workflow.workItemSystem(), workflow.workItemKey(), comment, reasonCode));
    }

    private void moveTicket(Workflow workflow, WorkItemTransitionTarget target, String reasonCode) {
        workItemTransitionService.transition(workflow, target, reasonCode);
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
            Objects.requireNonNullElse(command.resumeTrigger(), defaultResumeTrigger(blockerType, phase, requestedFrom)),
            details,
            jsonCodec.toJson(details)
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

    private PublishCodeChangesCommand buildPublishCommand(Workflow workflow, ReceiveAgentEventCommand command) {
        Map<String, Object> context = jsonCodec.toMap(workflow.contextJson());
        Map<String, Object> workItem = context.get("workItem") instanceof Map<?, ?> current
            ? current.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                entry -> String.valueOf(entry.getKey()),
                Map.Entry::getValue
            ))
            : Map.of();
        List<String> repositories = extractRepositories(context, workItem);
        String title = workItem.get("title") == null ? workflow.workItemKey() : String.valueOf(workItem.get("title"));
        return new PublishCodeChangesCommand(
            workflow.id(),
            workflow.workItemKey(),
            title,
            repositories,
            command.summary(),
            command.artifacts() == null ? Map.of() : command.artifacts()
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRepositories(Map<String, Object> context, Map<String, Object> workItem) {
        Object repositories = context.get("repositories");
        if (repositories instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }

        Object workItemRepositories = workItem.get("repositories");
        if (workItemRepositories instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }

        String repository = context.get("repository") instanceof String currentRepository && !currentRepository.isBlank()
            ? currentRepository
            : null;
        return repository == null ? List.of() : List.of(repository);
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

    private void registerCodeChangeReference(Workflow workflow, CodeChangeRef codeChange, CodeChangeStatus status) {
        ExternalReference reference = externalReferenceStore
            .findByReference(ExternalReferenceType.CODE_CHANGE, codeChange.system(), codeChange.externalId())
            .orElseGet(() -> ExternalReference.create(
                UUID.randomUUID(),
                workflow.id(),
                ExternalReferenceType.CODE_CHANGE,
                codeChange.system(),
                codeChange.externalId(),
                codeChange.url(),
                jsonCodec.toJson(buildCodeChangeMetadata(codeChange, status)),
                Instant.now()
            ));
        reference = reference.update(codeChange.url(), jsonCodec.toJson(buildCodeChangeMetadata(codeChange, status)));
        externalReferenceStore.save(reference);
    }

    private Map<String, Object> buildCodeChangeMetadata(CodeChangeRef codeChange, CodeChangeStatus status) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("repository", codeChange.repository());
        metadata.put("sourceBranch", codeChange.sourceBranch());
        metadata.put("targetBranch", codeChange.targetBranch());
        metadata.put("status", status.name());
        return metadata;
    }

    private record InputRequiredPayload(
        BlockerType blockerType,
        String reasonCode,
        String summary,
        String suggestedComment,
        RequestedFrom requestedFrom,
        ResumeTrigger resumeTrigger,
        Map<String, Object> details,
        String detailsJson
    ) {
    }

    private record UpdatedState(AgentRun run, Workflow workflow) {
    }
}
