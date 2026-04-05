package io.devflow.application.config;

import io.smallrye.config.ConfigMapping;
@ConfigMapping(prefix = "devflow")
public interface ApplicationConfig {

    Workflow workflow();

    Workspace workspace();

    interface Workflow {
        String definitionId();
    }

    interface Workspace {
        String runRoot();
    }
}
