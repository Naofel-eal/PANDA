package io.nud.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nud.application.config.ApplicationConfig;
import io.nud.support.ReflectionTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceLayoutServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given a workflow workspace when layout is resolved then directories and repository paths follow the business naming rules")
    void givenWorkflowWorkspace_whenLayoutIsResolved_thenDirectoriesAndRepositoryPathsFollowBusinessNamingRules() {
        WorkspaceLayoutService service = new WorkspaceLayoutService();
        ReflectionTestSupport.setField(service, "config", config(tempDir));
        UUID workflowId = UUID.randomUUID();

        service.ensureDirectories(workflowId);
        Map<String, Object> description = service.describe(workflowId);

        assertTrue(Files.exists(tempDir));
        assertEquals(tempDir, service.workspaceRoot());
        assertEquals(tempDir, service.runRoot());
        assertEquals(tempDir.toString(), description.get("projectRoot"));
        assertEquals(tempDir.resolve("front-test"), service.repositoryDirectory(workflowId, "Naofel-eal/front-test"));
        assertEquals(tempDir.resolve("repo-name"), service.repositoryDirectory(workflowId, " weird/repo name "));
        assertEquals(tempDir.resolve("standalone"), service.repositoryDirectory(workflowId, "standalone"));
        assertEquals(tempDir.resolve("unknown"), service.repositoryDirectory(workflowId, " "));
        service.logResolvedLayout(workflowId);
    }

    @Test
    @DisplayName("Given the configured workspace root is already a file when directories are prepared then NUD surfaces a clear setup error")
    void givenTheConfiguredWorkspaceRootIsAlreadyAFile_whenDirectoriesArePrepared_thenNUDSurfacesAClearSetupError() throws Exception {
        Path invalidRoot = tempDir.resolve("workspace.txt");
        Files.writeString(invalidRoot, "occupied");
        WorkspaceLayoutService service = new WorkspaceLayoutService();
        ReflectionTestSupport.setField(service, "config", config(invalidRoot));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.ensureDirectories(UUID.randomUUID()));

        assertTrue(exception.getMessage().contains("Unable to create workspace directories"));
    }

    private ApplicationConfig config(Path runRoot) {
        return new ApplicationConfig() {
            @Override
            public Workspace workspace() {
                return () -> runRoot.toString();
            }
        };
    }
}
