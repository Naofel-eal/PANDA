package io.devflow.infrastructure.persistence.postgres.repository;

import io.devflow.domain.agent.AgentRunStatus;
import io.devflow.infrastructure.persistence.postgres.entity.AgentRunEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class AgentRunRepository implements PanacheRepositoryBase<AgentRunEntity, UUID> {

    public Optional<AgentRunEntity> findActiveRun() {
        return find("status in ?1 order by createdAt asc", List.of(AgentRunStatus.STARTING, AgentRunStatus.RUNNING)).firstResultOptional();
    }

    public Optional<AgentRunEntity> findByWorkflowAndStatuses(UUID workflowId, List<AgentRunStatus> statuses) {
        return find("workflowId = ?1 and status in ?2 order by createdAt desc", workflowId, statuses).firstResultOptional();
    }

    public List<AgentRunEntity> findByWorkflow(UUID workflowId) {
        return list("workflowId = ?1 order by createdAt desc", workflowId);
    }
}
