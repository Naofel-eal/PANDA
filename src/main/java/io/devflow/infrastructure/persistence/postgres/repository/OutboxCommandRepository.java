package io.devflow.infrastructure.persistence.postgres.repository;

import io.devflow.domain.messaging.OutboxCommandStatus;
import io.devflow.infrastructure.persistence.postgres.entity.OutboxCommandEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class OutboxCommandRepository implements PanacheRepositoryBase<OutboxCommandEntity, UUID> {

    public Optional<OutboxCommandEntity> findNextPending() {
        return find("status = ?1 order by createdAt asc", OutboxCommandStatus.PENDING).firstResultOptional();
    }
}
