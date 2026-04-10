package io.panda.infrastructure.agent.opencode;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "panda.agent-runtime")
public interface OpenCodeRuntimeConfig {

    String baseUrl();

    @WithName("hard-timeout-minutes")
    @WithDefault("15")
    int hardTimeoutMinutes();

    @WithName("stale-timeout-buffer-minutes")
    @WithDefault("5")
    int staleTimeoutBufferMinutes();
}
