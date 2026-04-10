package io.nud.domain.model.codehost;

public record CodeChangeRef(
    String system,
    String externalId,
    String repository,
    String url,
    String sourceBranch,
    String targetBranch
) {
}
