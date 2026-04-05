package io.devflow.domain.workspace;

public record RepositoryWorkspace(
    String repository,
    String projectRoot
) {
}
