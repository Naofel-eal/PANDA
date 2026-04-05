package io.devflow.infrastructure.persistence.postgres.repository;

import io.devflow.infrastructure.persistence.postgres.entity.InboxEventEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class InboxEventRepository implements PanacheRepositoryBase<InboxEventEntity, UUID> {

    public Optional<InboxEventEntity> findBySourceEvent(String sourceSystem, String sourceEventType, String sourceEventId) {
        return find("sourceSystem = ?1 and sourceEventType = ?2 and sourceEventId = ?3",
            sourceSystem, sourceEventType, sourceEventId).firstResultOptional();
    }
}
