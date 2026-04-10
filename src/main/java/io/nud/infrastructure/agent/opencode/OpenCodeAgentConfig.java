package io.nud.infrastructure.agent.opencode;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

@ConfigMapping(prefix = "nud.agent")
public interface OpenCodeAgentConfig {

    Optional<String> model();

    Optional<String> smallModel();

    Optional<String> openAiApiKey();

    Optional<String> anthropicApiKey();

    Optional<String> geminiApiKey();

    Optional<String> copilotGithubToken();
}
