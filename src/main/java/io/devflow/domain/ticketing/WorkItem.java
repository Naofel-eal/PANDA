package io.devflow.domain.ticketing;

import java.util.List;

public record WorkItem(
    String key,
    String type,
    String title,
    String description,
    String status,
    String url,
    List<String> labels,
    List<String> repositories
) {

    public WorkItem {
        labels = labels == null ? List.of() : List.copyOf(labels);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }

    public WorkItemRef toReference(String system) {
        return new WorkItemRef(system, key, url);
    }
}
