package io.devflow.application.usecase;

import io.devflow.application.port.persistence.AgentEventStore;
import io.devflow.application.port.persistence.AgentRunStore;
import io.devflow.application.port.persistence.ExternalReferenceStore;
import io.devflow.application.port.persistence.WorkflowBlockerStore;
import io.devflow.application.port.persistence.WorkflowEventStore;
import io.devflow.application.port.persistence.WorkflowStore;
import io.devflow.application.port.support.JsonCodec;
import io.devflow.application.query.DashboardAgentRunView;
import io.devflow.application.query.DashboardBlockerView;
import io.devflow.application.query.DashboardCodeChangeView;
import io.devflow.application.query.DashboardRunEventView;
import io.devflow.application.query.DashboardTimelineEntryView;
import io.devflow.application.query.TicketDashboardView;
import io.devflow.domain.workflow.BlockerStatus;
import io.devflow.domain.codehost.CodeChangeStatus;
import io.devflow.domain.codehost.ExternalReferenceType;
import io.devflow.domain.workflow.WorkflowAuditType;
import io.devflow.domain.workflow.WorkflowStatus;
import io.devflow.domain.agent.AgentEvent;
import io.devflow.domain.agent.AgentRun;
import io.devflow.domain.codehost.ExternalReference;
import io.devflow.domain.workflow.Workflow;
import io.devflow.domain.workflow.WorkflowBlocker;
import io.devflow.domain.workflow.WorkflowEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class DashboardQueryService {

    @Inject
    WorkflowStore workflowStore;

    @Inject
    WorkflowBlockerStore workflowBlockerStore;

    @Inject
    AgentRunStore agentRunStore;

    @Inject
    AgentEventStore agentEventStore;

    @Inject
    ExternalReferenceStore externalReferenceStore;

    @Inject
    WorkflowEventStore workflowEventStore;

    @Inject
    JsonCodec jsonCodec;

    public List<TicketDashboardView> listTickets() {
        return workflowStore.listAll().stream()
            .sorted(Comparator
                .comparing((Workflow workflow) -> isActive(workflow.status()) ? 0 : 1)
                .thenComparing(Workflow::updatedAt, Comparator.reverseOrder()))
            .map(this::toTicketView)
            .toList();
    }

    private TicketDashboardView toTicketView(Workflow workflow) {
        Map<String, Object> context = jsonCodec.toMap(workflow.contextJson());
        Map<String, Object> workItem = readMap(context.get("workItem"));
        List<String> repositories = readStringList(context.get("repositories"), workItem.get("repositories"));

        List<WorkflowBlocker> blockers = workflowBlockerStore.findBlockersByWorkflowId(workflow.id());
        List<AgentRun> runs = agentRunStore.findRunsByWorkflow(workflow.id());
        List<ExternalReference> references = externalReferenceStore.findByWorkflow(workflow.id());
        List<WorkflowEvent> workflowEvents = workflowEventStore.findEventsByWorkflowId(workflow.id());

        List<DashboardAgentRunView> runViews = runs.stream()
            .map(this::toRunView)
            .toList();

        DashboardAgentRunView currentRun = runViews.stream()
            .filter(run -> switch (run.status()) {
                case PENDING, STARTING, RUNNING, WAITING_INPUT -> true;
                case COMPLETED, FAILED, CANCELLED -> false;
            })
            .findFirst()
            .orElse(runViews.isEmpty() ? null : runViews.getFirst());

        DashboardBlockerView currentBlocker = blockers.stream()
            .filter(blocker -> blocker.status() == BlockerStatus.OPEN)
            .findFirst()
            .map(this::toBlockerView)
            .orElse(null);

        List<DashboardCodeChangeView> codeChanges = references.stream()
            .filter(reference -> reference.refType() == ExternalReferenceType.CODE_CHANGE)
            .map(this::toCodeChangeView)
            .sorted(Comparator.comparing(DashboardCodeChangeView::createdAt, Comparator.reverseOrder()))
            .toList();

        List<DashboardTimelineEntryView> timeline = workflowEvents.stream()
            .map(this::toTimelineEntry)
            .toList();

        return new TicketDashboardView(
            workflow.id(),
            workflow.workItemSystem(),
            workflow.workItemKey(),
            readString(workItem.get("title"), workflow.workItemKey()),
            readString(workItem.get("description"), ""),
            readString(workItem.get("url"), null),
            workflow.phase(),
            workflow.status(),
            workflow.createdAt(),
            workflow.updatedAt(),
            repositories,
            currentBlocker,
            currentRun,
            runViews,
            codeChanges,
            timeline
        );
    }

    private DashboardRunEventView toRunEventView(AgentEvent event) {
        Map<String, Object> payload = jsonCodec.toMap(event.eventPayloadJson());
        return new DashboardRunEventView(
            event.eventType(),
            event.occurredAt(),
            readSummary(payload, friendlyTitleForAgentEvent(event.eventType()))
        );
    }

    private DashboardAgentRunView toRunView(AgentRun run) {
        List<DashboardRunEventView> events = agentEventStore.findByAgentRunId(run.id()).stream()
            .map(this::toRunEventView)
            .toList();
        return new DashboardAgentRunView(
            run.id(),
            run.phase(),
            run.status(),
            run.createdAt(),
            run.startedAt(),
            run.endedAt(),
            run.providerRunRef(),
            events
        );
    }

    private DashboardBlockerView toBlockerView(WorkflowBlocker blocker) {
        return new DashboardBlockerView(
            blocker.type(),
            blocker.reasonCode(),
            blocker.summary(),
            blocker.requestedFrom(),
            blocker.status(),
            blocker.openedAt(),
            blocker.resolvedAt()
        );
    }

    private DashboardCodeChangeView toCodeChangeView(ExternalReference reference) {
        Map<String, Object> metadata = jsonCodec.toMap(reference.metadataJson());
        String statusValue = readString(metadata.get("status"), CodeChangeStatus.OPEN.name());
        CodeChangeStatus status = CodeChangeStatus.valueOf(statusValue);
        return new DashboardCodeChangeView(
            readString(metadata.get("repository"), reference.externalId()),
            reference.externalId(),
            reference.url(),
            readString(metadata.get("targetBranch"), null),
            status,
            reference.createdAt()
        );
    }

    private DashboardTimelineEntryView toTimelineEntry(WorkflowEvent event) {
        Map<String, Object> payload = jsonCodec.toMap(event.eventPayloadJson());
        return new DashboardTimelineEntryView(
            event.occurredAt(),
            categoryForAuditEvent(event.eventType()),
            friendlyTitleForAuditEvent(event.eventType()),
            readSummary(payload, friendlyTitleForAuditEvent(event.eventType()))
        );
    }

    private String categoryForAuditEvent(WorkflowAuditType type) {
        return switch (type) {
            case AGENT_RUN_ENQUEUED, AGENT_RUN_CANCEL_ENQUEUED, AGENT_RUN_STARTED, AGENT_PROGRESS_REPORTED, AGENT_RUN_COMPLETED, AGENT_RUN_FAILED,
                AGENT_RUN_CANCELLED, WORKFLOW_INPUT_REQUIRED -> "agent";
            case WORK_ITEM_COMMENT_REQUESTED, WORK_ITEM_TRANSITION_REQUESTED, WORK_ITEM_NOT_ELIGIBLE, WORK_ITEM_INCOMPLETE, WORK_ITEM_READY_FOR_INFORMATION_COLLECTION,
                WORK_ITEM_COMMENT_NEW, WORK_ITEM_COMMENT_UPDATED, WORK_ITEM_STATUS_CHANGED -> "ticket";
            case CODE_CHANGE_REVIEW_COMMENT_NEW, CODE_CHANGE_REVIEW_COMMENT_UPDATED, CODE_CHANGE_MERGED, CODE_CHANGE_CLOSED_UNMERGED -> "code";
            case DEPLOYMENT_AVAILABLE, BUSINESS_VALIDATION_REPORTED -> "validation";
            case RECONCILIATION_REQUESTED, MANUAL_RESUME_REQUESTED, MANUAL_CANCEL_REQUESTED -> "system";
        };
    }

    private String friendlyTitleForAgentEvent(io.devflow.domain.agent.AgentEventType type) {
        return switch (type) {
            case RUN_STARTED -> "Agent started";
            case PROGRESS_REPORTED -> "Progress reported";
            case INPUT_REQUIRED -> "External input required";
            case COMPLETED -> "Agent completed";
            case FAILED -> "Agent failed";
            case CANCELLED -> "Agent cancelled";
        };
    }

    private String friendlyTitleForAuditEvent(WorkflowAuditType type) {
        return switch (type) {
            case AGENT_RUN_ENQUEUED -> "Agent run queued";
            case AGENT_RUN_CANCEL_ENQUEUED -> "Agent run cancellation queued";
            case AGENT_RUN_STARTED -> "Agent started";
            case AGENT_PROGRESS_REPORTED -> "Progress reported";
            case WORKFLOW_INPUT_REQUIRED -> "Workflow blocked for input";
            case AGENT_RUN_COMPLETED -> "Agent completed";
            case AGENT_RUN_FAILED -> "Agent failed";
            case AGENT_RUN_CANCELLED -> "Agent cancelled";
            case WORK_ITEM_COMMENT_REQUESTED -> "Ticket comment requested";
            case WORK_ITEM_TRANSITION_REQUESTED -> "Ticket transition requested";
            case WORK_ITEM_NOT_ELIGIBLE -> "Ticket not eligible";
            case WORK_ITEM_INCOMPLETE -> "Ticket incomplete";
            case WORK_ITEM_READY_FOR_INFORMATION_COLLECTION -> "Ticket ready";
            case WORK_ITEM_COMMENT_NEW -> "New ticket comment";
            case WORK_ITEM_COMMENT_UPDATED -> "Updated ticket comment";
            case WORK_ITEM_STATUS_CHANGED -> "Ticket status changed";
            case CODE_CHANGE_REVIEW_COMMENT_NEW -> "New code review comment";
            case CODE_CHANGE_REVIEW_COMMENT_UPDATED -> "Updated code review comment";
            case CODE_CHANGE_MERGED -> "Pull request merged";
            case CODE_CHANGE_CLOSED_UNMERGED -> "Pull request closed without merge";
            case DEPLOYMENT_AVAILABLE -> "Deployment available";
            case BUSINESS_VALIDATION_REPORTED -> "Business validation reported";
            case RECONCILIATION_REQUESTED -> "Reconciliation requested";
            case MANUAL_RESUME_REQUESTED -> "Manual resume requested";
            case MANUAL_CANCEL_REQUESTED -> "Manual cancel requested";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, element) -> normalized.put(String.valueOf(key), element));
        return normalized;
    }

    private List<String> readStringList(Object primaryValue, Object fallbackValue) {
        if (primaryValue instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        if (fallbackValue instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    private String readString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String readSummary(Map<String, Object> payload, String fallback) {
        Object summary = payload.get("summary");
        if (summary instanceof String text && !text.isBlank()) {
            return text;
        }
        Object comment = payload.get("comment");
        if (comment instanceof String text && !text.isBlank()) {
            return text;
        }
        Object objective = payload.get("objective");
        if (objective instanceof String text && !text.isBlank()) {
            return text;
        }
        Object reasonCode = payload.get("reasonCode");
        if (reasonCode instanceof String text && !text.isBlank()) {
            return text;
        }
        return fallback;
    }

    private boolean isActive(WorkflowStatus status) {
        return switch (status) {
            case ACTIVE, WAITING_EXTERNAL_INPUT, WAITING_SYSTEM, BLOCKED -> true;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
