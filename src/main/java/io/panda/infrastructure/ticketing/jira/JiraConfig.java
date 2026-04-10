package io.panda.infrastructure.ticketing.jira;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "panda.jira")
public interface JiraConfig {

    String baseUrl();

    String userEmail();

    String apiToken();

    String epicKey();

    String todoStatus();

    String inProgressStatus();

    String blockedStatus();

    String reviewStatus();

    String validateStatus();

    String doneStatus();

    int pollIntervalMinutes();

    int pollMaxResults();
}
