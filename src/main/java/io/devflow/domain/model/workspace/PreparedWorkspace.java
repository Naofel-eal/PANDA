package io.devflow.domain.model.workspace;

import java.util.List;

public record PreparedWorkspace(
    String projectRoot,
    List<RepositoryWorkspace> repositories
) {

    public PreparedWorkspace {
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }
}
