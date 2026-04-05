package io.devflow.infrastructure.codehost.github;

import io.smallrye.config.ConfigMapping;
import java.util.List;

@ConfigMapping(prefix = "devflow.github")
public interface GitHubConfig {

    String apiUrl();

    String token();

    String defaultBaseBranch();

    String commitUserName();

    String commitUserEmail();

    int pollIntervalMinutes();

    List<Repository> repositories();

    interface Repository {
        String name();

        String baseBranch();
    }
}
