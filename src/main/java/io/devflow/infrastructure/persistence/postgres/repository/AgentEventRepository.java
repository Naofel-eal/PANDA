package io.devflow.infrastructure.persistence.postgres.repository;

import io.devflow.infrastructure.persistence.postgres.entity.AgentEventEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class AgentEventRepository implements PanacheRepositoryBase<AgentEventEntity, UUID> {

    public Optional<AgentEventEntity> findByEventId(String eventId) {
        return find("eventId", eventId).firstResultOptional();
    }

    public List<AgentEventEntity> findByAgentRunId(UUID agentRunId) {
        return list("agentRunId = ?1 order by occurredAt asc", agentRunId);
    }
}
