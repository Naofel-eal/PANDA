package io.panda.infrastructure.codehost.github;

import io.smallrye.config.ConfigMapping;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "panda.github")
public interface GitHubConfig {

    String apiUrl();

    String token();

    String defaultBaseBranch();

    String commitUserName();

    String commitUserEmail();

    int pollIntervalMinutes();

    Optional<String> appId();

    Optional<String> appPrivateKey();

    Optional<String> appInstallationId();

    List<Repository> repositories();

    interface Repository {
        String name();

        String baseBranch();
    }
}
