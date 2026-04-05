package io.devflow.domain.codehost;

public record CodeChangeRef(
    String system,
    String externalId,
    String repository,
    String url,
    String sourceBranch,
    String targetBranch
) {
}
