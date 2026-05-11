package io.panda.domain.model.ticketing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record WorkItem(
    String key,
    String type,
    String title,
    String description,
    String status,
    String url,
    List<String> labels,
    List<String> repositories,
    Instant updatedAt,
    List<WorkItemAttachment> attachments
) {

    public WorkItem(
        String key, String type, String title, String description,
        String status, String url, List<String> labels,
        List<String> repositories, Instant updatedAt
    ) {
        this(key, type, title, description, status, url, labels, repositories, updatedAt, List.of());
    }

    public WorkItem {
        labels = labels == null ? List.of() : List.copyOf(labels);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public boolean isEligible() {
        return !isBlank(title) && !isBlank(description);
    }

    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (isBlank(title)) {
            missing.add("title");
        }
        if (isBlank(description)) {
            missing.add("description");
        }
        return missing;
    }

    public WorkItemRef toReference(String system) {
        return new WorkItemRef(system, key, url);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
