package io.nud.application.config;

import io.smallrye.config.ConfigMapping;
@ConfigMapping(prefix = "nud")
public interface ApplicationConfig {

    Workspace workspace();

    interface Workspace {
        String runRoot();
    }
}
