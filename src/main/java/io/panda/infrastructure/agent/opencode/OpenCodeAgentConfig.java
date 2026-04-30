package io.panda.infrastructure.agent.opencode;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

@ConfigMapping(prefix = "panda.agent")
public interface OpenCodeAgentConfig {

    Optional<String> model();

    Optional<String> smallModel();

    Optional<String> openAiApiKey();

    Optional<String> anthropicApiKey();

    Optional<String> geminiApiKey();

    Optional<String> copilotGithubToken();

    Optional<String> awsRegion();

    Optional<String> llmHubClientId();

    Optional<String> llmHubClientSecret();

    Optional<String> llmHubTenantId();

    Optional<String> llmHubArn();

    Optional<String> llmHubResource();
}
