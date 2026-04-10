package io.panda.domain.model.workspace;

public record RepositoryWorkspace(
    String repository,
    String projectRoot
) {
}
