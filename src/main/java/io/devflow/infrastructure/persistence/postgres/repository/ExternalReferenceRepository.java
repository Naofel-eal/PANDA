package io.devflow.infrastructure.persistence.postgres.repository;

import io.devflow.domain.codehost.ExternalReferenceType;
import io.devflow.infrastructure.persistence.postgres.entity.ExternalReferenceEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ExternalReferenceRepository implements PanacheRepositoryBase<ExternalReferenceEntity, UUID> {

    public Optional<ExternalReferenceEntity> findByReference(ExternalReferenceType type, String system, String externalId) {
        return find("refType = ?1 and system = ?2 and externalId = ?3", type, system, externalId).firstResultOptional();
    }

    public List<ExternalReferenceEntity> findByWorkflow(UUID workflowId) {
        return list("workflowId", workflowId);
    }

    public List<ExternalReferenceEntity> findByType(ExternalReferenceType type) {
        return list("refType", type);
    }
}
