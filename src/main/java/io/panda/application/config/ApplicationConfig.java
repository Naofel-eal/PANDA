package io.panda.application.config;

import io.smallrye.config.ConfigMapping;
@ConfigMapping(prefix = "panda")
public interface ApplicationConfig {

    Workspace workspace();

    interface Workspace {
        String runRoot();
    }
}
