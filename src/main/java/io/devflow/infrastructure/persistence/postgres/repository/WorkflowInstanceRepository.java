package io.devflow.infrastructure.persistence.postgres.repository;

import io.devflow.domain.workflow.WorkflowStatus;
import io.devflow.infrastructure.persistence.postgres.entity.WorkflowInstanceEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WorkflowInstanceRepository implements PanacheRepositoryBase<WorkflowInstanceEntity, UUID> {

    public Optional<WorkflowInstanceEntity> findByWorkItem(String system, String key) {
        return find("workItemSystem = ?1 and workItemKey = ?2", system, key).firstResultOptional();
    }

    public List<WorkflowInstanceEntity> findWaitingSystemOrdered() {
        return list("status = ?1 order by updatedAt asc", WorkflowStatus.WAITING_SYSTEM);
    }
}
