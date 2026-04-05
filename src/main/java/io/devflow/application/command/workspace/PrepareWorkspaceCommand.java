package io.devflow.application.command.workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PrepareWorkspaceCommand(
    UUID workflowId,
    List<String> repositories,
    Map<String, String> preferredBranches
) {

    public PrepareWorkspaceCommand {
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        preferredBranches = preferredBranches == null ? Map.of() : new LinkedHashMap<>(preferredBranches);
    }
}
