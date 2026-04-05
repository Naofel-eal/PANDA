package io.devflow.application.usecase;

import io.devflow.application.command.ticketing.CommentWorkItemCommand;
import io.devflow.application.command.workflow.WorkflowSignalCommand;
import io.devflow.application.config.ApplicationConfig;
import io.devflow.application.port.codehost.CodeHostPort;
import io.devflow.application.port.persistence.ExternalReferenceStore;
import io.devflow.application.port.persistence.InboxEventStore;
import io.devflow.application.port.persistence.WorkflowBlockerStore;
import io.devflow.application.port.persistence.WorkflowStore;
import io.devflow.application.port.support.HashGenerator;
import io.devflow.application.port.support.JsonCodec;
import io.devflow.application.port.ticketing.TicketingPort;
import io.devflow.application.service.CommentDeduplicationService;
import io.devflow.application.service.EligibilityService;
import io.devflow.application.service.WorkItemTransitionService;
import io.devflow.application.service.WorkflowAuditService;
import io.devflow.application.service.WorkspaceLayoutService;
import io.devflow.domain.workflow.BlockerType;
import io.devflow.domain.codehost.CodeChangeStatus;
import io.devflow.domain.ticketing.CommentFreshness;
import io.devflow.domain.ticketing.ExternalCommentParentType;
import io.devflow.domain.ticketing.WorkItemTransitionTarget;
import io.devflow.domain.codehost.ExternalReferenceType;
import io.devflow.domain.workflow.RequestedFrom;
import io.devflow.domain.workflow.ResumeTrigger;
import io.devflow.domain.workflow.WorkflowAuditType;
import io.devflow.domain.workflow.WorkflowPhase;
import io.devflow.domain.workflow.WorkflowStatus;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.codehost.ExternalReference;
import io.devflow.domain.messaging.InboxEvent;
import io.devflow.domain.workflow.Workflow;
import io.devflow.domain.workflow.WorkflowBlocker;
import io.devflow.domain.ticketing.WorkItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkflowSignalService {

    private static final Logger LOG = Logger.getLogger(WorkflowSignalService.class);

    @Inject
    ApplicationConfig config;

    @Inject
    WorkflowStore workflowStore;

    @Inject
    WorkflowBlockerStore workflowBlockerStore;

    @Inject
    ExternalReferenceStore externalReferenceStore;

    @Inject
    InboxEventStore inboxEventStore;

    @Inject
    EligibilityService eligibilityService;

    @Inject
    CommentDeduplicationService commentDeduplicationService;

    @Inject
    AgentRunService agentRunService;

    @Inject
    TicketingPort ticketingPort;

    @Inject
    CodeHostPort codeHostPort;

    @Inject
    WorkflowAuditService workflowAuditService;

    @Inject
    JsonCodec jsonCodec;

    @Inject
    HashGenerator hashGenerator;

    @Inject
    WorkspaceLayoutService workspaceLayoutService;

    @Inject
    WorkItemTransitionService workItemTransitionService;

    @Transactional
    public Optional<Workflow> handle(WorkflowSignalCommand command) {
        LOG.infof(
            "Handling workflow signal %s from %s (eventId=%s)",
            command.type(),
            command.sourceSystem(),
            command.sourceEventId()
        );
        InboxRegistration inboxRegistration = registerInbox(command);
        if (inboxRegistration.duplicate()) {
            LOG.infof("Ignoring duplicate workflow signal %s from %s", command.sourceEventId(), command.sourceSystem());
            return Optional.empty();
        }

        InboxEvent inboxEvent = inboxRegistration.event();
        try {
            Optional<Workflow> workflow = switch (command.type()) {
                case WORK_ITEM_DISCOVERED, WORK_ITEM_UPDATED -> handleWorkItemUpsert(command);
                case WORK_ITEM_COMMENT_RECEIVED -> handleWorkItemComment(command);
                case WORK_ITEM_STATUS_CHANGED -> handleWorkItemStatusChanged(command);
                case CODE_CHANGE_REVIEW_COMMENT_RECEIVED -> handleCodeChangeReviewComment(command);
                case CODE_CHANGE_MERGED -> handleCodeChangeMerged(command);
                case CODE_CHANGE_CLOSED_UNMERGED -> handleCodeChangeClosed(command);
                case DEPLOYMENT_AVAILABLE -> handleDeploymentAvailable(command);
                case BUSINESS_VALIDATION_REPORTED -> handleBusinessValidationReported(command);
                case RECONCILIATION_REQUESTED -> handleReconciliation(command);
                case MANUAL_RESUME_REQUESTED -> handleManualResume(command);
                case MANUAL_CANCEL_REQUESTED -> handleManualCancel(command);
            };

            if (inboxEvent != null) {
                inboxEvent = inboxEventStore.save(inboxEvent.markProcessed(Instant.now()));
            }

            return workflow.map(current -> {
                return workflowStore.save(current.touch(Instant.now()));
            });
        } catch (RuntimeException exception) {
            if (inboxEvent != null) {
                inboxEvent = inboxEventStore.save(inboxEvent.markFailed(Instant.now()));
            }
            throw exception;
        }
    }

    private Optional<Workflow> handleWorkItemUpsert(WorkflowSignalCommand command) {
        WorkItem workItem = requireWorkItem(command);
        LOG.infof("Upserting ticket %s from %s", workItem.key(), command.sourceSystem());
        EligibilityService.EligibilityDecision decision = eligibilityService.evaluate(workItem);
        Optional<Workflow> existing = workflowStore.findByWorkItem(command.sourceSystem(), workItem.key());

        Workflow workflow = existing.orElseGet(() -> createWorkflow(command.sourceSystem(), workItem.key()));
        workflow = updateWorkItemContext(workflow, workItem, command.sourceSystem());
        registerExternalReference(workflow, ExternalReferenceType.WORK_ITEM, command.sourceSystem(), workItem.key(), workItem.url(), workItem);

        if (!decision.hasEnoughInformation()) {
            String comment = "Missing required information: " + String.join(", ", decision.missingFields()) + ".";
            workflow = openOrUpdateBlocker(
                workflow,
                BlockerType.MISSING_TICKET_INFORMATION,
                "MISSING_REQUIRED_FIELDS",
                comment,
                comment,
                RequestedFrom.BUSINESS,
                ResumeTrigger.WORK_ITEM_COMMENT_RECEIVED,
                null
            );
            workflow = workflow.moveTo(WorkflowPhase.INFORMATION_COLLECTION, WorkflowStatus.WAITING_EXTERNAL_INPUT, Instant.now());
            moveTicket(workflow, WorkItemTransitionTarget.BLOCKED, "MISSING_REQUIRED_FIELDS");
            requestTicketComment(workflow, comment, "MISSING_REQUIRED_FIELDS");
            workflowAuditService.record(workflow.id(), WorkflowAuditType.WORK_ITEM_INCOMPLETE, command, command.sourceSystem(), command.sourceEventId());
            return Optional.of(workflow);
        }

        java.util.List<String> resolvedRepositories = codeHostPort.configuredRepositories();
        if (resolvedRepositories.isEmpty()) {
            String comment = "No repository is configured in Devflow for this environment.";
            workflow = openOrUpdateBlocker(
                workflow,
                BlockerType.MISSING_REPOSITORY_MAPPING,
                "NO_CONFIGURED_REPOSITORY",
                comment,
                comment,
                RequestedFrom.BUSINESS,
                ResumeTrigger.WORK_ITEM_COMMENT_RECEIVED,
                jsonCodec.toJson(Map.of("configuredRepositories", List.of()))
            );
            workflow = workflow.moveTo(WorkflowPhase.INFORMATION_COLLECTION, WorkflowStatus.WAITING_EXTERNAL_INPUT, Instant.now());
            moveTicket(workflow, WorkItemTransitionTarget.BLOCKED, "NO_CONFIGURED_REPOSITORY");
            requestTicketComment(workflow, comment, "NO_CONFIGURED_REPOSITORY");
            return Optional.of(workflow);
        }
        workflow = updateResolvedRepositoriesContext(workflow, resolvedRepositories);

        if (existing.isPresent() && workflow.phase() != WorkflowPhase.INFORMATION_COLLECTION) {
            return Optional.of(workflow);
        }

        workflow = resolveOpenBlocker(workflow);
        workflow = agentRunService.enqueueStartRun(
            workflow,
            WorkflowPhase.INFORMATION_COLLECTION,
            "Assess work item " + workflow.workItemKey() + " before implementation",
            buildInputSnapshot(workflow, command)
        );
        LOG.infof("Ticket %s queued for agent information collection", workflow.workItemKey());
        workflowAuditService.record(
            workflow.id(),
            WorkflowAuditType.WORK_ITEM_READY_FOR_INFORMATION_COLLECTION,
            command,
            command.sourceSystem(),
            command.sourceEventId()
        );
        return Optional.of(workflow);
    }

    private Optional<Workflow> handleWorkItemComment(WorkflowSignalCommand command) {
        CommentFreshness freshness = commentDeduplicationService.register(command.sourceSystem(), requireComment(command));
        if (freshness == CommentFreshness.DUPLICATE) {
            return Optional.empty();
        }

        Workflow workflow = resolveWorkflowFromCommentSignal(command)
            .orElseThrow(() -> new NotFoundException("No workflow found for work item comment"));

        workflowAuditService.record(
            workflow.id(),
            switch (freshness) {
                case NEW -> WorkflowAuditType.WORK_ITEM_COMMENT_NEW;
                case UPDATED -> WorkflowAuditType.WORK_ITEM_COMMENT_UPDATED;
                case DUPLICATE -> throw new IllegalStateException("Duplicate comments should already be filtered");
            },
            command,
            command.sourceSystem(),
            command.sourceEventId()
        );

        if (workflow.status() == WorkflowStatus.WAITING_EXTERNAL_INPUT) {
            LOG.infof("Resuming ticket %s after incoming comment", workflow.workItemKey());
            workflow = resolveOpenBlocker(workflow);
            WorkflowPhase nextPhase = workflow.phase() == WorkflowPhase.INFORMATION_COLLECTION
                ? WorkflowPhase.INFORMATION_COLLECTION
                : WorkflowPhase.IMPLEMENTATION;
            workflow = agentRunService.enqueueStartRun(
                workflow,
                nextPhase,
                nextPhase == WorkflowPhase.INFORMATION_COLLECTION
                    ? "Reassess work item after new comment"
                    : "Address new feedback on work item " + workflow.workItemKey(),
                buildInputSnapshot(workflow, command)
            );
        }

        return Optional.of(workflow);
    }

    private Optional<Workflow> handleWorkItemStatusChanged(WorkflowSignalCommand command) {
        WorkItem workItem = requireWorkItem(command);
        Workflow workflow = workflowStore.findByWorkItem(command.sourceSystem(), workItem.key())
            .orElseThrow(() -> new NotFoundException("No workflow found for work item"));

        if (workItem.status() != null) {
            String normalized = workItem.status().toLowerCase();
            if (normalized.contains("cancel")) {
                workflow = workflow.markCancelled(Instant.now());
            } else if (normalized.contains("done") || normalized.contains("closed")) {
                workflow = workflow.markCompleted(Instant.now());
            }
        }

        workflowAuditService.record(workflow.id(), WorkflowAuditType.WORK_ITEM_STATUS_CHANGED, command, command.sourceSystem(), command.sourceEventId());
        return Optional.of(workflow);
    }

    private Optional<Workflow> handleCodeChangeReviewComment(WorkflowSignalCommand command) {
        CommentFreshness freshness = commentDeduplicationService.register(command.sourceSystem(), requireComment(command));
        if (freshness == CommentFreshness.DUPLICATE) {
            return Optional.empty();
        }

        CodeChangeRef codeChange = requireCodeChange(command);
        Workflow workflow = resolveWorkflowByCodeChange(codeChange)
            .orElseThrow(() -> new NotFoundException("No workflow found for code change"));

        registerCodeChangeReference(workflow, codeChange, CodeChangeStatus.OPEN);
        LOG.infof(
            "Received review comment for pull request %s linked to ticket %s",
            codeChange.externalId(),
            workflow.workItemKey()
        );
        workflow = agentRunService.enqueueStartRun(
            workflow,
            WorkflowPhase.IMPLEMENTATION,
            "Address technical review feedback for " + workflow.workItemKey(),
            buildInputSnapshot(workflow, command)
        );
        workflowAuditService.record(
            workflow.id(),
            freshness == CommentFreshness.NEW
                ? WorkflowAuditType.CODE_CHANGE_REVIEW_COMMENT_NEW
                : WorkflowAuditType.CODE_CHANGE_REVIEW_COMMENT_UPDATED,
            command,
            command.sourceSystem(),
            command.sourceEventId()
        );
        return Optional.of(workflow);
    }

    private Optional<Workflow> handleCodeChangeMerged(WorkflowSignalCommand command) {
        CodeChangeRef codeChange = requireCodeChange(command);
        Workflow workflow = resolveWorkflowByCodeChange(codeChange)
            .orElseThrow(() -> new NotFoundException("No workflow found for code change"));

        registerCodeChangeReference(workflow, codeChange, CodeChangeStatus.MERGED);
        LOG.infof("Pull request %s merged for ticket %s", codeChange.externalId(), workflow.workItemKey());

        if (!allCodeChangesMerged(workflow.id())) {
            workflow = workflow.moveTo(WorkflowPhase.TECHNICAL_VALIDATION, WorkflowStatus.WAITING_SYSTEM, Instant.now());
            workflowAuditService.record(workflow.id(), WorkflowAuditType.CODE_CHANGE_MERGED, command, command.sourceSystem(), command.sourceEventId());
            return Optional.of(workflow);
        }

        workflow = resolveOpenBlocker(workflow);
        workflow = workflow.moveTo(WorkflowPhase.BUSINESS_VALIDATION, WorkflowStatus.WAITING_EXTERNAL_INPUT, Instant.now());
        workflow = openOrUpdateBlocker(
            workflow,
            BlockerType.WAITING_BUSINESS_FEEDBACK,
            "READY_FOR_BUSINESS_VALIDATION",
            "All pull requests have been merged. The ticket is ready for validation.",
            "All pull requests have been merged. The ticket is ready for validation.",
            RequestedFrom.BUSINESS,
            ResumeTrigger.WORK_ITEM_COMMENT_RECEIVED,
            null
        );
        moveTicket(workflow, WorkItemTransitionTarget.TO_VALIDATE, "ALL_PULL_REQUESTS_MERGED");
        requestTicketComment(workflow, "All pull requests have been merged. The ticket is ready for validation.", "ALL_PULL_REQUESTS_MERGED");
        LOG.infof("Ticket %s moved to business validation after all pull requests were merged", workflow.workItemKey());

        workflowAuditService.record(workflow.id(), WorkflowAuditType.CODE_CHANGE_MERGED, command, command.sourceSystem(), command.sourceEventId());
        return Optional.of(workflow);
    }

    private Optional<Workflow> handleCodeChangeClosed(WorkflowSignalCommand command) {
        CodeChangeRef codeChange = requireCodeChange(command);
        Workflow workflow = resolveWorkflowByCodeChange(codeChange)
            .orElseThrow(() -> new NotFoundException("No workflow found for code change"));
        registerCodeChangeReference(workflow, codeChange, CodeChangeStatus.CLOSED_UNMERGED);
        workflow = workflow.moveTo(workflow.phase(), WorkflowStatus.BLOCKED, Instant.now());
        LOG.warnf("Pull request %s was closed without merge for ticket %s", codeChange.externalId(), workflow.workItemKey());
        moveTicket(workflow, WorkItemTransitionTarget.BLOCKED, "CODE_CHANGE_CLOSED_UNMERGED");
        workflowAuditService.record(workflow.id(), WorkflowAuditType.CODE_CHANGE_CLOSED_UNMERGED, command, command.sourceSystem(), command.sourceEventId());
        return Optional.of(workflow);
    }

    private Optional<Workflow> handleDeploymentAvailable(WorkflowSignalCommand command) {
        Workflow workflow = resolveWorkflow(command)
            .orElseThrow(() -> new NotFoundException("No workflow found for deployment event"));

        if (workflow.phase() == WorkflowPhase.BUSINESS_VALIDATION) {
            workflow = workflow.moveTo(WorkflowPhase.BUSINESS_VALIDATION, WorkflowStatus.WAITING_EXTERNAL_INPUT, Instant.now());
            workflow = openOrUpdateBlocker(
                workflow,
                BlockerType.WAITING_BUSINESS_FEEDBACK,
                "DEPLOYMENT_AVAILABLE",
                "Deployment is available for business validation.",
                "Deployment is available. The work item is ready for business validation.",
                RequestedFrom.BUSINESS,
                ResumeTrigger.WORK_ITEM_COMMENT_RECEIVED,
                command.deployment() == null ? null : jsonCodec.toJson(command.deployment())
            );
            requestTicketComment(workflow, "Deployment is available. The work item is ready for business validation.", "DEPLOYMENT_AVAILABLE");
        }

        workflowAuditService.record(workflow.id(), WorkflowAuditType.DEPLOYMENT_AVAILABLE, command, command.sourceSystem(), command.sourceEventId());
        return Optional.of(workflow);
    }

    private Optional<Workflow> handleBusinessValidationReported(WorkflowSignalCommand command) {
        Workflow workflow = resolveWorkflow(command)
            .orElseThrow(() -> new NotFoundException("No workflow found for business validation event"));

        if ("APPROVED".equalsIgnoreCase(Objects.requireNonNull(command.businessValidation(), "businessValidation is required").result())) {
            workflow = resolveOpenBlocker(workflow);
            workflow = workflow.markCompleted(Instant.now());
            LOG.infof("Business validation approved for ticket %s", workflow.workItemKey());
            moveTicket(workflow, WorkItemTransitionTarget.DONE, "BUSINESS_VALIDATION_APPROVED");
        } else {
            workflow = resolveOpenBlocker(workflow);
            LOG.infof("Business validation rejected for ticket %s, restarting implementation", workflow.workItemKey());
            workflow = agentRunService.enqueueStartRun(
                workflow,
                WorkflowPhase.IMPLEMENTATION,
                "Address business validation feedback for " + workflow.workItemKey(),
                buildInputSnapshot(workflow, command)
            );
        }

        workflowAuditService.record(workflow.id(), WorkflowAuditType.BUSINESS_VALIDATION_REPORTED, command, command.sourceSystem(), command.sourceEventId());
        return Optional.of(workflow);
    }

    private Optional<Workflow> handleReconciliation(WorkflowSignalCommand command) {
        return resolveWorkflow(command).map(workflow -> {
            workflowAuditService.record(workflow.id(), WorkflowAuditType.RECONCILIATION_REQUESTED, command, command.sourceSystem(), command.sourceEventId());
            return workflow;
        });
    }

    private Optional<Workflow> handleManualResume(WorkflowSignalCommand command) {
        Workflow workflow = resolveWorkflow(command)
            .orElseThrow(() -> new NotFoundException("No workflow found for manual resume"));
        workflow = resolveOpenBlocker(workflow);
        WorkflowPhase nextPhase = workflow.phase() == WorkflowPhase.INFORMATION_COLLECTION
            ? WorkflowPhase.INFORMATION_COLLECTION
            : WorkflowPhase.IMPLEMENTATION;
        workflow = agentRunService.enqueueStartRun(
            workflow,
            nextPhase,
            "Manual resume for " + workflow.workItemKey(),
            buildInputSnapshot(workflow, command)
        );
        workflowAuditService.record(workflow.id(), WorkflowAuditType.MANUAL_RESUME_REQUESTED, command, command.sourceSystem(), command.sourceEventId());
        return Optional.of(workflow);
    }

    private Optional<Workflow> handleManualCancel(WorkflowSignalCommand command) {
        Workflow workflow = resolveWorkflow(command)
            .orElseThrow(() -> new NotFoundException("No workflow found for manual cancel"));
        workflow = workflow.markCancelled(Instant.now());
        workflowAuditService.record(workflow.id(), WorkflowAuditType.MANUAL_CANCEL_REQUESTED, command, command.sourceSystem(), command.sourceEventId());
        return Optional.of(workflow);
    }

    private InboxRegistration registerInbox(WorkflowSignalCommand command) {
        if (command.sourceEventId() == null || command.sourceEventId().isBlank()) {
            return new InboxRegistration(null, false);
        }

        Optional<InboxEvent> existing = inboxEventStore.findBySourceEvent(command.sourceSystem(), command.type().name(), command.sourceEventId());
        if (existing.isPresent()) {
            return new InboxRegistration(existing.get(), true);
        }

        InboxEvent inboxEvent = InboxEvent.receive(
            UUID.randomUUID(),
            command.sourceSystem(),
            command.type().name(),
            command.sourceEventId(),
            hashGenerator.sha256(jsonCodec.toJson(command)),
            Instant.now()
        );
        return new InboxRegistration(inboxEventStore.save(inboxEvent), false);
    }

    private Workflow createWorkflow(String sourceSystem, String workItemKey) {
        return workflowStore.save(
            Workflow.create(
                UUID.randomUUID(),
                "default",
                config.workflow().definitionId(),
                new io.devflow.domain.ticketing.WorkItemRef(sourceSystem, workItemKey, null),
                Instant.now()
            )
        );
    }

    private Workflow updateWorkItemContext(Workflow workflow, WorkItem workItem, String sourceSystem) {
        Map<String, Object> context = new LinkedHashMap<>(jsonCodec.toMap(workflow.contextJson()));
        context.put("workItem", workItem);
        context.put("workItemSystem", sourceSystem);
        return workflow.replaceContext(jsonCodec.toJson(context), Instant.now());
    }

    private Workflow updateResolvedRepositoriesContext(Workflow workflow, java.util.List<String> repositories) {
        Map<String, Object> context = new LinkedHashMap<>(jsonCodec.toMap(workflow.contextJson()));
        context.put("repositories", repositories);
        context.remove("repository");
        return workflow.replaceContext(jsonCodec.toJson(context), Instant.now());
    }

    private void registerExternalReference(
        Workflow workflow,
        ExternalReferenceType type,
        String system,
        String externalId,
        String url,
        Object metadata
    ) {
        ExternalReference reference = externalReferenceStore.findByReference(type, system, externalId)
            .orElseGet(() -> ExternalReference.create(
                UUID.randomUUID(),
                workflow.id(),
                type,
                system,
                externalId,
                url,
                jsonCodec.toJson(metadata),
                Instant.now()
            ));
        reference = reference.update(url, jsonCodec.toJson(metadata));
        externalReferenceStore.save(reference);
    }

    private void registerCodeChangeReference(Workflow workflow, CodeChangeRef codeChange, CodeChangeStatus status) {
        ExternalReference reference = externalReferenceStore.findByReference(
                ExternalReferenceType.CODE_CHANGE,
                codeChange.system(),
                codeChange.externalId()
            )
            .orElseGet(() -> ExternalReference.create(
                UUID.randomUUID(),
                workflow.id(),
                ExternalReferenceType.CODE_CHANGE,
                codeChange.system(),
                codeChange.externalId(),
                codeChange.url(),
                jsonCodec.toJson(Map.of()),
                Instant.now()
            ));

        Map<String, Object> metadata = new LinkedHashMap<>(jsonCodec.toMap(reference.metadataJson()));
        metadata.put("repository", codeChange.repository());
        metadata.put("sourceBranch", codeChange.sourceBranch());
        metadata.put("targetBranch", codeChange.targetBranch());
        metadata.put("status", status.name());
        reference = reference.update(codeChange.url(), jsonCodec.toJson(metadata));
        externalReferenceStore.save(reference);
    }

    private boolean allCodeChangesMerged(UUID workflowId) {
        List<ExternalReference> codeChanges = externalReferenceStore.findByWorkflow(workflowId).stream()
            .filter(reference -> reference.refType() == ExternalReferenceType.CODE_CHANGE)
            .toList();
        if (codeChanges.isEmpty()) {
            return false;
        }
        return codeChanges.stream().allMatch(reference -> {
            Object status = jsonCodec.toMap(reference.metadataJson()).get("status");
            return CodeChangeStatus.MERGED.name().equals(status);
        });
    }

    private Workflow openOrUpdateBlocker(
        Workflow workflow,
        BlockerType type,
        String reasonCode,
        String summary,
        String suggestedComment,
        RequestedFrom requestedFrom,
        ResumeTrigger resumeTrigger,
        String detailsJson
    ) {
        WorkflowBlocker blocker = workflowBlockerStore.findOpenByWorkflowId(workflow.id())
            .orElseGet(() -> WorkflowBlocker.open(
                UUID.randomUUID(),
                workflow.id(),
                workflow.phase(),
                type,
                reasonCode,
                summary,
                suggestedComment,
                requestedFrom,
                resumeTrigger,
                detailsJson,
                Instant.now()
            ));

        blocker = blocker.refresh(workflow.phase(), type, reasonCode, summary, suggestedComment, requestedFrom, resumeTrigger, detailsJson);
        blocker = workflowBlockerStore.save(blocker);
        return workflow.attachBlocker(blocker.id(), workflow.status(), Instant.now());
    }

    private Workflow resolveOpenBlocker(Workflow workflow) {
        workflowBlockerStore.findOpenByWorkflowId(workflow.id()).ifPresent(blocker -> {
            workflowBlockerStore.save(blocker.resolve(Instant.now()));
        });
        return workflow.clearBlocker(Instant.now());
    }

    private Optional<Workflow> resolveWorkflow(WorkflowSignalCommand command) {
        if (command.workflowId() != null) {
            return workflowStore.findById(command.workflowId());
        }
        if (command.workItem() != null) {
            return workflowStore.findByWorkItem(command.sourceSystem(), command.workItem().key());
        }
        if (command.codeChange() != null) {
            return resolveWorkflowByCodeChange(command.codeChange());
        }
        return Optional.empty();
    }

    private Optional<Workflow> resolveWorkflowByCodeChange(CodeChangeRef codeChange) {
        return externalReferenceStore.findByReference(ExternalReferenceType.CODE_CHANGE, codeChange.system(), codeChange.externalId())
            .flatMap(reference -> workflowStore.findById(reference.workflowId()));
    }

    private Optional<Workflow> resolveWorkflowFromCommentSignal(WorkflowSignalCommand command) {
        if (command.workItem() != null) {
            return workflowStore.findByWorkItem(command.sourceSystem(), command.workItem().key());
        }
        if (command.comment() != null && ExternalCommentParentType.WORK_ITEM.matches(command.comment().parentType())) {
            return workflowStore.findByWorkItem(command.sourceSystem(), command.comment().parentId());
        }
        return resolveWorkflow(command);
    }

    private Map<String, Object> buildInputSnapshot(Workflow workflow, WorkflowSignalCommand command) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowId", workflow.id().toString());
        snapshot.put("phase", workflow.phase().name());
        snapshot.put("workItemSystem", workflow.workItemSystem());
        snapshot.put("workItemKey", workflow.workItemKey());
        snapshot.put("workspace", workspaceLayoutService.describe(workflow.id()));
        snapshot.put("workflowContext", sanitizeWorkflowContextForAgent(jsonCodec.toMap(workflow.contextJson())));
        if (command.workItem() != null) {
            snapshot.put("workItem", command.workItem());
        }
        if (command.comment() != null) {
            snapshot.put("comment", command.comment());
        }
        if (command.codeChange() != null) {
            snapshot.put("codeChange", command.codeChange());
        }
        if (command.businessValidation() != null) {
            snapshot.put("businessValidation", command.businessValidation());
        }
        return snapshot;
    }

    private Map<String, Object> sanitizeWorkflowContextForAgent(Map<String, Object> workflowContext) {
        Map<String, Object> sanitized = new LinkedHashMap<>(workflowContext == null ? Map.of() : workflowContext);
        sanitized.remove("repository");
        return sanitized;
    }

    private WorkItem requireWorkItem(WorkflowSignalCommand command) {
        return Objects.requireNonNull(command.workItem(), "workItem is required");
    }

    private io.devflow.domain.ticketing.IncomingComment requireComment(WorkflowSignalCommand command) {
        return Objects.requireNonNull(command.comment(), "comment is required");
    }

    private CodeChangeRef requireCodeChange(WorkflowSignalCommand command) {
        return Objects.requireNonNull(command.codeChange(), "codeChange is required");
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

    private record InboxRegistration(InboxEvent event, boolean duplicate) {
    }
}
