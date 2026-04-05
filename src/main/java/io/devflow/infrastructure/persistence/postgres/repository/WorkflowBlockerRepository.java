package io.devflow.infrastructure.persistence.postgres.repository;

import io.devflow.domain.workflow.BlockerStatus;
import io.devflow.infrastructure.persistence.postgres.entity.WorkflowBlockerEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WorkflowBlockerRepository implements PanacheRepositoryBase<WorkflowBlockerEntity, UUID> {

    public Optional<WorkflowBlockerEntity> findOpenByWorkflowId(UUID workflowId) {
        return find("workflowId = ?1 and status = ?2", workflowId, BlockerStatus.OPEN).firstResultOptional();
    }

    public List<WorkflowBlockerEntity> findByWorkflowId(UUID workflowId) {
        return list("workflowId = ?1 order by openedAt desc", workflowId);
    }
}
