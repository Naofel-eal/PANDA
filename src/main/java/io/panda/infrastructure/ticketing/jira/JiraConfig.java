package io.panda.infrastructure.ticketing.jira;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "panda.jira")
public interface JiraConfig {

    String baseUrl();

    String apiToken();

    String backlogStatus();

    String todoStatus();

    String inProgressStatus();

    String blockedStatus();

    String reviewStatus();

    String validateStatus();

    String doneStatus();

    int pollIntervalMinutes();

    int pollMaxResults();

    @WithDefault("customfield_10020")
    String sprintField();

    Optional<String> serviceAccountId();

    String projectKey();
}
