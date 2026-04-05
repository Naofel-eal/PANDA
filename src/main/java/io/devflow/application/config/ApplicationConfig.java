package io.devflow.application.config;

import io.smallrye.config.ConfigMapping;
@ConfigMapping(prefix = "devflow")
public interface ApplicationConfig {

    Workspace workspace();

    interface Workspace {
        String runRoot();
    }
}
