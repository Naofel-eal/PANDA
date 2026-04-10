package io.nud.application.service;

import io.nud.application.config.ApplicationConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkspaceLayoutService {

    private static final Logger LOG = Logger.getLogger(WorkspaceLayoutService.class);

    @Inject
    ApplicationConfig config;

    public void ensureDirectories(UUID workflowId) {
        try {
            Files.createDirectories(workspaceRoot());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create workspace directories", exception);
        }
    }

    public Map<String, Object> describe(UUID workflowId) {
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("projectRoot", workspaceRoot().toString());
        workspace.put("workspaceRoot", workspaceRoot().toString());
        return workspace;
    }

    public Path workspaceRoot() {
        return Path.of(config.workspace().runRoot());
    }

    public Path runRoot() {
        return workspaceRoot();
    }

    public Path repositoryDirectory(UUID workflowId, String repositorySlug) {
        return workspaceRoot().resolve(repositoryDirectoryName(repositorySlug));
    }

    private String repositoryDirectoryName(String repositorySlug) {
        if (repositorySlug == null || repositorySlug.isBlank()) {
            return "unknown";
        }
        String trimmed = repositorySlug.trim();
        int lastSlash = trimmed.lastIndexOf('/');
        String repositoryName = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        return repositoryName.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    public void logResolvedLayout(UUID workflowId) {
        LOG.debugf(
            "Resolved workspace layout for workflow %s: workspaceRoot=%s",
            workflowId,
            workspaceRoot()
        );
    }
}
