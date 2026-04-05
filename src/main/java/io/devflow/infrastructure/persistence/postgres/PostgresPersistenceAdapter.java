package io.devflow.infrastructure.persistence.postgres;

import io.devflow.application.port.persistence.AgentEventStore;
import io.devflow.application.port.persistence.AgentRunStore;
import io.devflow.application.port.persistence.ExternalCommentStore;
import io.devflow.application.port.persistence.ExternalReferenceStore;
import io.devflow.application.port.persistence.InboxEventStore;
import io.devflow.application.port.persistence.OutboxCommandStore;
import io.devflow.application.port.persistence.WorkflowBlockerStore;
import io.devflow.application.port.persistence.WorkflowEventStore;
import io.devflow.application.port.persistence.WorkflowStore;
import io.devflow.domain.agent.AgentRunStatus;
import io.devflow.domain.codehost.ExternalReferenceType;
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
import io.devflow.infrastructure.persistence.postgres.repository.AgentEventRepository;
import io.devflow.infrastructure.persistence.postgres.repository.AgentRunRepository;
import io.devflow.infrastructure.persistence.postgres.repository.ExternalCommentRepository;
import io.devflow.infrastructure.persistence.postgres.repository.ExternalReferenceRepository;
import io.devflow.infrastructure.persistence.postgres.repository.InboxEventRepository;
import io.devflow.infrastructure.persistence.postgres.repository.OutboxCommandRepository;
import io.devflow.infrastructure.persistence.postgres.repository.WorkflowBlockerRepository;
import io.devflow.infrastructure.persistence.postgres.repository.WorkflowEventRepository;
import io.devflow.infrastructure.persistence.postgres.repository.WorkflowInstanceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PostgresPersistenceAdapter implements
    WorkflowStore,
    WorkflowBlockerStore,
    ExternalReferenceStore,
    InboxEventStore,
    ExternalCommentStore,
    AgentRunStore,
    AgentEventStore,
    OutboxCommandStore,
    WorkflowEventStore {

    @Inject
    PostgresPersistenceMapper mapper;

    @Inject
    WorkflowInstanceRepository workflowInstanceRepository;

    @Inject
    WorkflowBlockerRepository workflowBlockerRepository;

    @Inject
    ExternalReferenceRepository externalReferenceRepository;

    @Inject
    InboxEventRepository inboxEventRepository;

    @Inject
    ExternalCommentRepository externalCommentRepository;

    @Inject
    AgentRunRepository agentRunRepository;

    @Inject
    AgentEventRepository agentEventRepository;

    @Inject
    OutboxCommandRepository outboxCommandRepository;

    @Inject
    WorkflowEventRepository workflowEventRepository;

    @Override
    public Optional<Workflow> findById(UUID id) {
        return workflowInstanceRepository.findByIdOptional(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Workflow> findByWorkItem(String system, String key) {
        return workflowInstanceRepository.findByWorkItem(system, key).map(mapper::toDomain);
    }

    @Override
    public List<Workflow> listAll() {
        return workflowInstanceRepository.listAll().stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Workflow> findWaitingSystemOrdered() {
        return workflowInstanceRepository.findWaitingSystemOrdered().stream().map(mapper::toDomain).toList();
    }

    @Override
    public Workflow save(Workflow workflow) {
        WorkflowInstanceEntity entity = workflow.id() == null
            ? new WorkflowInstanceEntity()
            : workflowInstanceRepository.findByIdOptional(workflow.id()).orElseGet(WorkflowInstanceEntity::new);
        mapper.copyToEntity(workflow, entity);
        if (!entity.isPersistent()) {
            workflowInstanceRepository.persist(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<WorkflowBlocker> findOpenByWorkflowId(UUID workflowId) {
        return workflowBlockerRepository.findOpenByWorkflowId(workflowId).map(mapper::toDomain);
    }

    @Override
    public List<WorkflowBlocker> findBlockersByWorkflowId(UUID workflowId) {
        return workflowBlockerRepository.findByWorkflowId(workflowId).stream().map(mapper::toDomain).toList();
    }

    @Override
    public WorkflowBlocker save(WorkflowBlocker blocker) {
        WorkflowBlockerEntity entity = blocker.id() == null
            ? new WorkflowBlockerEntity()
            : workflowBlockerRepository.findByIdOptional(blocker.id()).orElseGet(WorkflowBlockerEntity::new);
        mapper.copyToEntity(blocker, entity);
        if (!entity.isPersistent()) {
            workflowBlockerRepository.persist(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<ExternalReference> findByReference(ExternalReferenceType type, String system, String externalId) {
        return externalReferenceRepository.findByReference(type, system, externalId).map(mapper::toDomain);
    }

    @Override
    public List<ExternalReference> findByWorkflow(UUID workflowId) {
        return externalReferenceRepository.findByWorkflow(workflowId).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<ExternalReference> findByType(ExternalReferenceType type) {
        return externalReferenceRepository.findByType(type).stream().map(mapper::toDomain).toList();
    }

    @Override
    public ExternalReference save(ExternalReference reference) {
        ExternalReferenceEntity entity = reference.id() == null
            ? new ExternalReferenceEntity()
            : externalReferenceRepository.findByIdOptional(reference.id()).orElseGet(ExternalReferenceEntity::new);
        mapper.copyToEntity(reference, entity);
        if (!entity.isPersistent()) {
            externalReferenceRepository.persist(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<InboxEvent> findBySourceEvent(String sourceSystem, String sourceEventType, String sourceEventId) {
        return inboxEventRepository.findBySourceEvent(sourceSystem, sourceEventType, sourceEventId).map(mapper::toDomain);
    }

    @Override
    public InboxEvent save(InboxEvent inboxEvent) {
        InboxEventEntity entity = inboxEvent.id() == null
            ? new InboxEventEntity()
            : inboxEventRepository.findByIdOptional(inboxEvent.id()).orElseGet(InboxEventEntity::new);
        mapper.copyToEntity(inboxEvent, entity);
        if (!entity.isPersistent()) {
            inboxEventRepository.persist(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<ExternalComment> findByComment(String sourceSystem, String commentId) {
        return externalCommentRepository.findByComment(sourceSystem, commentId).map(mapper::toDomain);
    }

    @Override
    public ExternalComment save(ExternalComment comment) {
        ExternalCommentEntity entity = comment.id() == null
            ? new ExternalCommentEntity()
            : externalCommentRepository.findByIdOptional(comment.id()).orElseGet(ExternalCommentEntity::new);
        mapper.copyToEntity(comment, entity);
        if (!entity.isPersistent()) {
            externalCommentRepository.persist(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<AgentRun> findRunById(UUID id) {
        return agentRunRepository.findByIdOptional(id).map(mapper::toDomain);
    }

    @Override
    public Optional<AgentRun> findActiveRun() {
        return agentRunRepository.findActiveRun().map(mapper::toDomain);
    }

    @Override
    public Optional<AgentRun> findByWorkflowAndStatuses(UUID workflowId, List<AgentRunStatus> statuses) {
        return agentRunRepository.findByWorkflowAndStatuses(workflowId, statuses).map(mapper::toDomain);
    }

    @Override
    public List<AgentRun> findRunsByWorkflow(UUID workflowId) {
        return agentRunRepository.findByWorkflow(workflowId).stream().map(mapper::toDomain).toList();
    }

    @Override
    public AgentRun save(AgentRun run) {
        AgentRunEntity entity = run.id() == null
            ? new AgentRunEntity()
            : agentRunRepository.findByIdOptional(run.id()).orElseGet(AgentRunEntity::new);
        mapper.copyToEntity(run, entity);
        if (!entity.isPersistent()) {
            agentRunRepository.persist(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<AgentEvent> findByEventId(String eventId) {
        return agentEventRepository.findByEventId(eventId).map(mapper::toDomain);
    }

    @Override
    public List<AgentEvent> findByAgentRunId(UUID agentRunId) {
        return agentEventRepository.findByAgentRunId(agentRunId).stream().map(mapper::toDomain).toList();
    }

    @Override
    public AgentEvent save(AgentEvent event) {
        AgentEventEntity entity = event.id() == null
            ? new AgentEventEntity()
            : agentEventRepository.findByIdOptional(event.id()).orElseGet(AgentEventEntity::new);
        mapper.copyToEntity(event, entity);
        if (!entity.isPersistent()) {
            agentEventRepository.persist(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<OutboxCommand> findNextPending() {
        return outboxCommandRepository.findNextPending().map(mapper::toDomain);
    }

    @Override
    public OutboxCommand save(OutboxCommand outboxCommand) {
        OutboxCommandEntity entity = outboxCommand.id() == null
            ? new OutboxCommandEntity()
            : outboxCommandRepository.findByIdOptional(outboxCommand.id()).orElseGet(OutboxCommandEntity::new);
        mapper.copyToEntity(outboxCommand, entity);
        if (!entity.isPersistent()) {
            outboxCommandRepository.persist(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public WorkflowEvent save(WorkflowEvent event) {
        WorkflowEventEntity entity = event.id() == null
            ? new WorkflowEventEntity()
            : workflowEventRepository.findByIdOptional(event.id()).orElseGet(WorkflowEventEntity::new);
        mapper.copyToEntity(event, entity);
        if (!entity.isPersistent()) {
            workflowEventRepository.persist(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public List<WorkflowEvent> findEventsByWorkflowId(UUID workflowId) {
        return workflowEventRepository.findByWorkflowId(workflowId).stream().map(mapper::toDomain).toList();
    }
}
