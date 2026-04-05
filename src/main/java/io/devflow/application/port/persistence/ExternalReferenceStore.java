package io.devflow.application.port.persistence;

import io.devflow.domain.codehost.ExternalReferenceType;
import io.devflow.domain.codehost.ExternalReference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalReferenceStore {

    Optional<ExternalReference> findByReference(ExternalReferenceType type, String system, String externalId);

    List<ExternalReference> findByWorkflow(UUID workflowId);

    List<ExternalReference> findByType(ExternalReferenceType type);

    ExternalReference save(ExternalReference reference);
}
