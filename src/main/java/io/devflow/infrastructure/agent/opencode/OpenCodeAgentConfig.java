package io.devflow.infrastructure.agent.opencode;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

@ConfigMapping(prefix = "devflow.agent")
public interface OpenCodeAgentConfig {

    Optional<String> model();

    Optional<String> smallModel();

    Optional<String> openAiApiKey();

    Optional<String> anthropicApiKey();

    Optional<String> geminiApiKey();

    Optional<String> copilotGithubToken();
}
