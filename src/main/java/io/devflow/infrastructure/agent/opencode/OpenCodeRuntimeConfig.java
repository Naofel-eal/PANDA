package io.devflow.infrastructure.agent.opencode;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "devflow.agent-runtime")
public interface OpenCodeRuntimeConfig {

    String baseUrl();
}
