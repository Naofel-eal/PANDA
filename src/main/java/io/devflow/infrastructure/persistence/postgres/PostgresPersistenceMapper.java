package io.devflow.infrastructure.persistence.postgres;

import io.devflow.domain.agent.AgentEvent;
import io.devflow.domain.agent.AgentRun;
import io.devflow.domain.ticketing.ExternalComment;
import io.devflow.domain.codehost.ExternalReference;
import io.devflow.domain.messaging.InboxEvent;
import io.devflow.domain.messaging.OutboxCommand;
import io.devflow.domain.workflow.Workflow;
import io.devflow.domain.workflow.WorkflowBlocker;
import io.devflow.domain.workflow.WorkflowEvent;
import io.devflow.infrastructure.persistence.postgres.entity.AgentEventEntity;
import io.devflow.infrastructure.persistence.postgres.entity.AgentRunEntity;
import io.devflow.infrastructure.persistence.postgres.entity.ExternalCommentEntity;
import io.devflow.infrastructure.persistence.postgres.entity.ExternalReferenceEntity;
import io.devflow.infrastructure.persistence.postgres.entity.InboxEventEntity;
import io.devflow.infrastructure.persistence.postgres.entity.OutboxCommandEntity;
import io.devflow.infrastructure.persistence.postgres.entity.WorkflowBlockerEntity;
import io.devflow.infrastructure.persistence.postgres.entity.WorkflowEventEntity;
import io.devflow.infrastructure.persistence.postgres.entity.WorkflowInstanceEntity;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PostgresPersistenceMapper {

    public Workflow toDomain(WorkflowInstanceEntity entity) {
        return Workflow.rehydrate(
            entity.id,
            entity.tenantId,
            entity.workflowDefinitionId,
            entity.workItemSystem,
            entity.workItemKey,
            entity.phase,
            entity.status,
            entity.currentBlockerId,
            entity.contextJson,
            entity.version,
            entity.createdAt,
            entity.updatedAt
        );
    }

    public void copyToEntity(Workflow source, WorkflowInstanceEntity target) {
        target.id = source.id();
        target.tenantId = source.tenantId();
        target.workflowDefinitionId = source.workflowDefinitionId();
        target.workItemSystem = source.workItemSystem();
        target.workItemKey = source.workItemKey();
        target.phase = source.phase();
        target.status = source.status();
        target.currentBlockerId = source.currentBlockerId();
        target.contextJson = source.contextJson();
        target.createdAt = source.createdAt();
        target.updatedAt = source.updatedAt();
    }

    public WorkflowBlocker toDomain(WorkflowBlockerEntity entity) {
        return WorkflowBlocker.rehydrate(
            entity.id,
            entity.workflowId,
            entity.phase,
            entity.type,
            entity.reasonCode,
            entity.summary,
            entity.suggestedComment,
            entity.requestedFrom,
            entity.resumeTrigger,
            entity.detailsJson,
            entity.openedAt,
            entity.resolvedAt,
            entity.status
        );
    }

    public void copyToEntity(WorkflowBlocker source, WorkflowBlockerEntity target) {
        target.id = source.id();
        target.workflowId = source.workflowId();
        target.phase = source.phase();
        target.type = source.type();
        target.reasonCode = source.reasonCode();
        target.summary = source.summary();
        target.suggestedComment = source.suggestedComment();
        target.requestedFrom = source.requestedFrom();
        target.resumeTrigger = source.resumeTrigger();
        target.detailsJson = source.detailsJson();
        target.openedAt = source.openedAt();
        target.resolvedAt = source.resolvedAt();
        target.status = source.status();
    }

    public ExternalReference toDomain(ExternalReferenceEntity entity) {
        return ExternalReference.rehydrate(
            entity.id,
            entity.workflowId,
            entity.refType,
            entity.system,
            entity.externalId,
            entity.url,
            entity.metadataJson,
            entity.createdAt
        );
    }

    public void copyToEntity(ExternalReference source, ExternalReferenceEntity target) {
        target.id = source.id();
        target.workflowId = source.workflowId();
        target.refType = source.refType();
        target.system = source.system();
        target.externalId = source.externalId();
        target.url = source.url();
        target.metadataJson = source.metadataJson();
        target.createdAt = source.createdAt();
    }

    public InboxEvent toDomain(InboxEventEntity entity) {
        return InboxEvent.rehydrate(
            entity.id,
            entity.sourceSystem,
            entity.sourceEventType,
            entity.sourceEventId,
            entity.payloadHash,
            entity.receivedAt,
            entity.processedAt,
            entity.status
        );
    }

    public void copyToEntity(InboxEvent source, InboxEventEntity target) {
        target.id = source.id();
        target.sourceSystem = source.sourceSystem();
        target.sourceEventType = source.sourceEventType();
        target.sourceEventId = source.sourceEventId();
        target.payloadHash = source.payloadHash();
        target.receivedAt = source.receivedAt();
        target.processedAt = source.processedAt();
        target.status = source.status();
    }

    public ExternalComment toDomain(ExternalCommentEntity entity) {
        return ExternalComment.rehydrate(
            entity.id,
            entity.sourceSystem,
            entity.commentId,
            entity.parentType,
            entity.parentId,
            entity.authorId,
            entity.payloadHash,
            entity.commentCreatedAt,
            entity.commentUpdatedAt,
            entity.firstSeenAt,
            entity.lastSeenAt
        );
    }

    public void copyToEntity(ExternalComment source, ExternalCommentEntity target) {
        target.id = source.id();
        target.sourceSystem = source.sourceSystem();
        target.commentId = source.commentId();
        target.parentType = source.parentType();
        target.parentId = source.parentId();
        target.authorId = source.authorId();
        target.payloadHash = source.payloadHash();
        target.commentCreatedAt = source.commentCreatedAt();
        target.commentUpdatedAt = source.commentUpdatedAt();
        target.firstSeenAt = source.firstSeenAt();
        target.lastSeenAt = source.lastSeenAt();
    }

    public AgentRun toDomain(AgentRunEntity entity) {
        return AgentRun.rehydrate(
            entity.id,
            entity.workflowId,
            entity.phase,
            entity.status,
            entity.inputSnapshotJson,
            entity.providerRunRef,
            entity.startedAt,
            entity.endedAt,
            entity.createdAt,
            entity.updatedAt
        );
    }

    public void copyToEntity(AgentRun source, AgentRunEntity target) {
        target.id = source.id();
        target.workflowId = source.workflowId();
        target.phase = source.phase();
        target.status = source.status();
        target.inputSnapshotJson = source.inputSnapshotJson();
        target.providerRunRef = source.providerRunRef();
        target.startedAt = source.startedAt();
        target.endedAt = source.endedAt();
        target.createdAt = source.createdAt();
        target.updatedAt = source.updatedAt();
    }

    public AgentEvent toDomain(AgentEventEntity entity) {
        return AgentEvent.rehydrate(
            entity.id,
            entity.agentRunId,
            entity.eventId,
            entity.eventType,
            entity.eventPayloadJson,
            entity.occurredAt,
            entity.processedAt,
            entity.status
        );
    }

    public void copyToEntity(AgentEvent source, AgentEventEntity target) {
        target.id = source.id();
        target.agentRunId = source.agentRunId();
        target.eventId = source.eventId();
        target.eventType = source.eventType();
        target.eventPayloadJson = source.eventPayloadJson();
        target.occurredAt = source.occurredAt();
        target.processedAt = source.processedAt();
        target.status = source.status();
    }

    public OutboxCommand toDomain(OutboxCommandEntity entity) {
        return OutboxCommand.rehydrate(
            entity.id,
            entity.workflowId,
            entity.agentRunId,
            entity.commandType,
            entity.payloadJson,
            entity.createdAt,
            entity.processedAt,
            entity.updatedAt,
            entity.status,
            entity.failureReason
        );
    }

    public void copyToEntity(OutboxCommand source, OutboxCommandEntity target) {
        target.id = source.id();
        target.workflowId = source.workflowId();
        target.agentRunId = source.agentRunId();
        target.commandType = source.commandType();
        target.payloadJson = source.payloadJson();
        target.createdAt = source.createdAt();
        target.processedAt = source.processedAt();
        target.updatedAt = source.updatedAt();
        target.status = source.status();
        target.failureReason = source.failureReason();
    }

    public WorkflowEvent toDomain(WorkflowEventEntity entity) {
        return WorkflowEvent.rehydrate(
            entity.id,
            entity.workflowId,
            io.devflow.domain.workflow.WorkflowAuditType.valueOf(entity.eventType),
            entity.eventPayloadJson,
            entity.occurredAt,
            entity.sourceSystem,
            entity.sourceEventId
        );
    }

    public void copyToEntity(WorkflowEvent source, WorkflowEventEntity target) {
        target.id = source.id();
        target.workflowId = source.workflowId();
        target.eventType = source.eventType().name();
        target.eventPayloadJson = source.eventPayloadJson();
        target.occurredAt = source.occurredAt();
        target.sourceSystem = source.sourceSystem();
        target.sourceEventId = source.sourceEventId();
    }
}
