package io.devflow.infrastructure.persistence.postgres.repository;

import io.devflow.infrastructure.persistence.postgres.entity.ExternalCommentEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ExternalCommentRepository implements PanacheRepositoryBase<ExternalCommentEntity, UUID> {

    public Optional<ExternalCommentEntity> findByComment(String sourceSystem, String commentId) {
        return find("sourceSystem = ?1 and commentId = ?2", sourceSystem, commentId).firstResultOptional();
    }
}
