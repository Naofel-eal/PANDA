package io.devflow.infrastructure.agent.opencode;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "devflow.agent-runtime")
public interface OpenCodeRuntimeConfig {

    String baseUrl();

    @WithDefault("20")
    int maxRunDurationMinutes();
}
