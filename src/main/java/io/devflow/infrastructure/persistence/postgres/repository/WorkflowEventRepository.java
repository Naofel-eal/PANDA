package io.devflow.infrastructure.persistence.postgres.repository;

import io.devflow.infrastructure.persistence.postgres.entity.WorkflowEventEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class WorkflowEventRepository implements PanacheRepositoryBase<WorkflowEventEntity, UUID> {

    public List<WorkflowEventEntity> findByWorkflowId(UUID workflowId) {
        return list("workflowId = ?1 order by occurredAt desc", workflowId);
    }
}
