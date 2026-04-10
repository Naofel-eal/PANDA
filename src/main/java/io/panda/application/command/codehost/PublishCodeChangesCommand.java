package io.panda.application.command.codehost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PublishCodeChangesCommand(
    UUID workflowId,
    String workItemKey,
    String workItemTitle,
    List<String> repositories,
    String summary,
    Map<String, Object> artifacts
) {

    public PublishCodeChangesCommand {
        repositories = repositories == null
            ? List.of()
            : repositories.stream().filter(current -> current != null && !current.isBlank()).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        artifacts = artifacts == null ? Map.of() : new LinkedHashMap<>(artifacts);
    }
}
