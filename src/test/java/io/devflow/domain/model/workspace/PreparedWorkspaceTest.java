package io.devflow.domain.model.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PreparedWorkspaceTest {

    @Test
    @DisplayName("Given repository workspaces when the prepared workspace is created then DevFlow keeps an immutable copy of the repository list")
    void givenRepositoryWorkspaces_whenThePreparedWorkspaceIsCreated_thenDevFlowKeepsAnImmutableCopyOfTheRepositoryList() {
        List<RepositoryWorkspace> repositories = new ArrayList<>();
        repositories.add(new RepositoryWorkspace("acme/api", "/workspace/acme-api"));

        PreparedWorkspace workspace = new PreparedWorkspace("/workspace", repositories);
        repositories.add(new RepositoryWorkspace("acme/web", "/workspace/acme-web"));

        assertEquals("/workspace", workspace.projectRoot());
        assertEquals(1, workspace.repositories().size());
        assertEquals("acme/api", workspace.repositories().getFirst().repository());
        assertThrows(UnsupportedOperationException.class, () -> workspace.repositories().add(
            new RepositoryWorkspace("acme/web", "/workspace/acme-web")
        ));
    }

    @Test
    @DisplayName("Given no repository workspace when the prepared workspace is created then DevFlow exposes an empty repository list")
    void givenNoRepositoryWorkspace_whenThePreparedWorkspaceIsCreated_thenDevFlowExposesAnEmptyRepositoryList() {
        PreparedWorkspace workspace = new PreparedWorkspace("/workspace", null);

        assertEquals(List.of(), workspace.repositories());
    }
}
