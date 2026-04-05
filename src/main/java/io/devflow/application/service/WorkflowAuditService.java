package io.devflow.application.service;

import io.devflow.application.port.persistence.WorkflowEventStore;
import io.devflow.application.port.support.JsonCodec;
import io.devflow.domain.workflow.WorkflowAuditType;
import io.devflow.domain.workflow.WorkflowEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class WorkflowAuditService {

    @Inject
    WorkflowEventStore workflowEventStore;

    @Inject
    JsonCodec jsonCodec;

    public void record(UUID workflowId, WorkflowAuditType eventType, Object payload, String sourceSystem, String sourceEventId) {
        WorkflowEvent event = WorkflowEvent.record(
            UUID.randomUUID(),
            workflowId,
            eventType,
            payload == null ? null : jsonCodec.toJson(payload),
            Instant.now(),
            sourceSystem,
            sourceEventId
        );
        workflowEventStore.save(event);
    }
}
