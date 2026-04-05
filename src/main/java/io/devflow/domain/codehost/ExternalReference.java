package io.devflow.domain.codehost;

import io.devflow.domain.codehost.ExternalReferenceType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ExternalReference {

    private final UUID id;
    private final UUID workflowId;
    private final ExternalReferenceType refType;
    private final String system;
    private final String externalId;
    private final String url;
    private final String metadataJson;
    private final Instant createdAt;

    private ExternalReference(
        UUID id,
        UUID workflowId,
        ExternalReferenceType refType,
        String system,
        String externalId,
        String url,
        String metadataJson,
        Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId must not be null");
        this.refType = Objects.requireNonNull(refType, "refType must not be null");
        this.system = Objects.requireNonNull(system, "system must not be null");
        this.externalId = Objects.requireNonNull(externalId, "externalId must not be null");
        this.url = url;
        this.metadataJson = metadataJson;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static ExternalReference create(
        UUID id,
        UUID workflowId,
        ExternalReferenceType refType,
        String system,
        String externalId,
        String url,
        String metadataJson,
        Instant createdAt
    ) {
        return new ExternalReference(id, workflowId, refType, system, externalId, url, metadataJson, createdAt);
    }

    public static ExternalReference rehydrate(
        UUID id,
        UUID workflowId,
        ExternalReferenceType refType,
        String system,
        String externalId,
        String url,
        String metadataJson,
        Instant createdAt
    ) {
        return new ExternalReference(id, workflowId, refType, system, externalId, url, metadataJson, createdAt);
    }

    public ExternalReference update(String url, String metadataJson) {
        return new ExternalReference(id, workflowId, refType, system, externalId, url, metadataJson, createdAt);
    }
}
