package io.panda.infrastructure.codehost.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.panda.application.command.codehost.PublishCodeChangesCommand;
import io.panda.application.command.workspace.PrepareWorkspaceCommand;
import io.panda.application.config.ApplicationConfig;
import io.panda.application.service.WorkspaceLayoutService;
import io.panda.domain.exception.DomainException;
import io.panda.infrastructure.support.JsonSupport;
import io.panda.support.ReflectionTestSupport;
import io.panda.support.StubHttpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubCodeHostAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given a configured repository and a preferred branch when PANDA prepares the workspace then the requested branch is cloned and later refreshed cleanly")
    void givenAConfiguredRepositoryAndAPreferredBranch_whenPANDAPreparesTheWorkspace_thenTheRequestedBranchIsClonedAndLaterRefreshedCleanly() throws Exception {
        Path remoteRoot = tempDir.resolve("remotes");
        createRemoteRepository(remoteRoot, "acme/api", true);
        configureGitRewrite(remoteRoot);

        try (StubHttpServer server = new StubHttpServer()) {
            GitHubCodeHostAdapter adapter = adapter(server.baseUrl(), tempDir.resolve("workspace"));

            var preparedWorkspace = adapter.prepareWorkspace(new PrepareWorkspaceCommand(
                UUID.randomUUID(),
                List.of(),
                Map.of("acme/api", "review-fixes")
            ));

            Path repositoryDirectory = Path.of(preparedWorkspace.repositories().getFirst().projectRoot());
            assertEquals(List.of("acme/api"), adapter.configuredRepositories());
            assertTrue(Files.exists(repositoryDirectory.resolve(".git")));
            assertEquals("review-fixes", git(repositoryDirectory, "rev-parse", "--abbrev-ref", "HEAD").trim());

            Files.writeString(repositoryDirectory.resolve("local.tmp"), "temp");

            adapter.prepareWorkspace(new PrepareWorkspaceCommand(
                UUID.randomUUID(),
                List.of("acme/api"),
                Map.of()
            ));

            assertEquals("main", git(repositoryDirectory, "rev-parse", "--abbrev-ref", "HEAD").trim());
            assertFalse(Files.exists(repositoryDirectory.resolve("local.tmp")));
        }
    }

    @Test
    @DisplayName("Given local changes without an open pull request when PANDA publishes code then the branch is pushed and a new pull request is created")
    void givenLocalChangesWithoutAnOpenPullRequest_whenPANDAPublishesCode_thenTheBranchIsPushedAndANewPullRequestIsCreated() throws Exception {
        Path remoteRoot = tempDir.resolve("remotes");
        createRemoteRepository(remoteRoot, "acme/api", true);
        configureGitRewrite(remoteRoot);

        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/repos/acme/api/pulls", 200, "[]");
            server.enqueue("POST", "/repos/acme/api/pulls", 201, """
                {"number":41,"html_url":"https://github.com/acme/api/pull/41"}
                """);
            GitHubCodeHostAdapter adapter = adapter(server.baseUrl(), tempDir.resolve("workspace"));
            var preparedWorkspace = adapter.prepareWorkspace(new PrepareWorkspaceCommand(
                UUID.randomUUID(),
                List.of("acme/api"),
                Map.of()
            ));
            Path repositoryDirectory = Path.of(preparedWorkspace.repositories().getFirst().projectRoot());
            Files.writeString(repositoryDirectory.resolve("README.md"), "main\nupdated\n");

            var changes = adapter.publish(new PublishCodeChangesCommand(
                UUID.randomUUID(),
                "SCRUM-50",
                "Improve publishing",
                List.of("acme/api"),
                "Business validation fix",
                Map.of("repoChanges", List.of(Map.of(
                    "repository", "acme/api",
                    "headBranch", "review-fixes",
                    "baseBranch", "main",
                    "commitMessage", "feat: publish changes",
                    "prTitle", "Improve publishing flow",
                    "prBody", "Please validate the publishing flow."
                )))
            ));

            assertEquals(1, changes.size());
            assertEquals("acme/api#41", changes.getFirst().externalId());
            assertEquals("panda/review-fixes", changes.getFirst().sourceBranch());
            assertEquals("main", changes.getFirst().targetBranch());
            assertEquals("main", git(repositoryDirectory, "rev-parse", "--abbrev-ref", "HEAD").trim());
            assertTrue(server.requests().get(1).body().contains("\"title\":\"Improve publishing flow [SCRUM-50]\""));
        }
    }

    @Test
    @DisplayName("Given local changes with an existing open pull request when PANDA publishes code then the existing pull request is reused")
    void givenLocalChangesWithAnExistingOpenPullRequest_whenPANDAPublishesCode_thenTheExistingPullRequestIsReused() throws Exception {
        Path remoteRoot = tempDir.resolve("remotes");
        createRemoteRepository(remoteRoot, "acme/api", true);
        configureGitRewrite(remoteRoot);

        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/repos/acme/api/pulls", 200, """
                [{"number":42,"html_url":"https://github.com/acme/api/pull/42"}]
                """);
            GitHubCodeHostAdapter adapter = adapter(server.baseUrl(), tempDir.resolve("workspace"));
            var preparedWorkspace = adapter.prepareWorkspace(new PrepareWorkspaceCommand(
                UUID.randomUUID(),
                List.of("acme/api"),
                Map.of()
            ));
            Path repositoryDirectory = Path.of(preparedWorkspace.repositories().getFirst().projectRoot());
            Files.writeString(repositoryDirectory.resolve("README.md"), "main\nsecond update\n");

            var changes = adapter.publish(new PublishCodeChangesCommand(
                UUID.randomUUID(),
                "SCRUM-51",
                "Reuse PR",
                List.of("acme/api"),
                "Existing PR should be reused",
                Map.of("repoChanges", List.of(Map.of(
                    "repository", "acme/api",
                    "headBranch", "review-fixes",
                    "baseBranch", "main"
                )))
            ));

            assertEquals(1, changes.size());
            assertEquals("acme/api#42", changes.getFirst().externalId());
            assertEquals(1, server.requests().size());
        }
    }

    @Test
    @DisplayName("Given no local repository changes when PANDA publishes code then the workflow is rejected with a clear business message")
    void givenNoLocalRepositoryChanges_whenPANDAPublishesCode_thenTheWorkflowIsRejectedWithAClearBusinessMessage() throws Exception {
        Path remoteRoot = tempDir.resolve("remotes");
        createRemoteRepository(remoteRoot, "acme/api", false);
        configureGitRewrite(remoteRoot);

        try (StubHttpServer server = new StubHttpServer()) {
            GitHubCodeHostAdapter adapter = adapter(server.baseUrl(), tempDir.resolve("workspace"));
            adapter.prepareWorkspace(new PrepareWorkspaceCommand(
                UUID.randomUUID(),
                List.of("acme/api"),
                Map.of()
            ));

            DomainException exception = assertThrows(DomainException.class, () -> adapter.publish(new PublishCodeChangesCommand(
                UUID.randomUUID(),
                "SCRUM-52",
                "No changes",
                List.of("acme/api"),
                "No effective change",
                Map.of()
            )));

            assertTrue(exception.getMessage().contains("No local changes were detected"));
        }
    }

    @Test
    @DisplayName("Given no repository mapping when PANDA publishes code then the request is rejected before any git operation")
    void givenNoRepositoryMapping_whenPANDAPublishesCode_thenTheRequestIsRejectedBeforeAnyGitOperation() {
        GitHubCodeHostAdapter adapter = adapter("http://127.0.0.1:9", tempDir.resolve("workspace"));

        DomainException exception = assertThrows(DomainException.class, () -> adapter.publish(new PublishCodeChangesCommand(
            UUID.randomUUID(),
            "SCRUM-53",
            "Missing repository",
            List.of(),
            "Summary",
            Map.of()
        )));

        assertTrue(exception.getMessage().contains("Missing GitHub repository mapping"));
    }

    @Test
    @DisplayName("Given a requested repository outside the configured scope when PANDA prepares a workspace then the request is rejected")
    void givenARequestedRepositoryOutsideTheConfiguredScope_whenPANDAPreparesAWorkspace_thenTheRequestIsRejected() {
        GitHubCodeHostAdapter adapter = adapter("http://127.0.0.1:9", tempDir.resolve("workspace"));

        DomainException exception = assertThrows(DomainException.class, () -> adapter.prepareWorkspace(
            new PrepareWorkspaceCommand(UUID.randomUUID(), List.of("acme/unknown"), Map.of())
        ));

        assertTrue(exception.getMessage().contains("No accessible GitHub repository configured"));
    }

    @Test
    @DisplayName("Given publication is requested before the repository workspace exists when PANDA publishes code then the request is rejected clearly")
    void givenPublicationIsRequestedBeforeTheRepositoryWorkspaceExists_whenPANDAPublishesCode_thenTheRequestIsRejectedClearly() {
        GitHubCodeHostAdapter adapter = adapter("http://127.0.0.1:9", tempDir.resolve("workspace"));

        DomainException exception = assertThrows(DomainException.class, () -> adapter.publish(new PublishCodeChangesCommand(
            UUID.randomUUID(),
            "SCRUM-54",
            "Missing workspace",
            List.of("acme/api"),
            "Summary",
            Map.of()
        )));

        assertTrue(exception.getMessage().contains("Repository workspace is missing"));
    }

    @Test
    @DisplayName("Given no explicit GitHub publishing instructions when PANDA publishes code then default branch naming and titles are generated from the ticket")
    void givenNoExplicitGitHubPublishingInstructions_whenPANDAPublishesCode_thenDefaultBranchNamingAndTitlesAreGeneratedFromTheTicket() throws Exception {
        Path remoteRoot = tempDir.resolve("remotes");
        createRemoteRepository(remoteRoot, "acme/api", false);
        configureGitRewrite(remoteRoot);

        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/repos/acme/api/pulls", 200, """
                [{"number":43,"html_url":"https://github.com/acme/api/pull/43"}]
                """);
            GitHubCodeHostAdapter adapter = adapter(server.baseUrl(), tempDir.resolve("workspace"));
            var preparedWorkspace = adapter.prepareWorkspace(new PrepareWorkspaceCommand(
                UUID.randomUUID(),
                List.of("acme/api"),
                Map.of()
            ));
            Path repositoryDirectory = Path.of(preparedWorkspace.repositories().getFirst().projectRoot());
            Files.writeString(repositoryDirectory.resolve("README.md"), "main\ndefault path\n");

            var changes = adapter.publish(new PublishCodeChangesCommand(
                UUID.randomUUID(),
                "SCRUM-55",
                "Polish defaults",
                List.of("acme/api"),
                "Implementation summary",
                Map.of()
            ));

            assertEquals("panda/scrum-55/acme-api", changes.getFirst().sourceBranch());
            assertEquals("acme/api#43", changes.getFirst().externalId());
        }
    }

    @Test
    @DisplayName("Given duplicate and blank GitHub repository mappings when PANDA lists accessible repositories then only distinct usable repositories remain")
    void givenDuplicateAndBlankGitHubRepositoryMappings_whenPANDAListsAccessibleRepositories_thenOnlyDistinctUsableRepositoriesRemain() {
        GitHubConfig config = new GitHubConfig() {
            @Override public String apiUrl() { return "http://github"; }
            @Override public String token() { return "github-token"; }
            @Override public String defaultBaseBranch() { return "main"; }
            @Override public String commitUserName() { return "PANDA"; }
            @Override public String commitUserEmail() { return "panda@example.com"; }
            @Override public int pollIntervalMinutes() { return 1; }
            @Override
            public List<Repository> repositories() {
                return List.of(
                    repository("acme/api", "main"),
                    repository(" ", "main"),
                    repository("acme/api", "develop"),
                    repository("acme/web", "main")
                );
            }
        };

        GitHubCodeHostAdapter adapter = adapter("http://127.0.0.1:9", tempDir.resolve("workspace"), config);

        assertEquals(List.of("acme/api", "acme/web"), adapter.configuredRepositories());
    }

    @Test
    @DisplayName("Given a blank branch instruction when PANDA publishes code then PANDA falls back to its default branch naming")
    void givenABlankBranchInstruction_whenPANDAPublishesCode_thenPANDAFallsBackToItsDefaultBranchNaming() throws Exception {
        Path remoteRoot = tempDir.resolve("remotes");
        createRemoteRepository(remoteRoot, "acme/api", false);
        configureGitRewrite(remoteRoot);

        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/repos/acme/api/pulls", 200, """
                [{"number":56,"html_url":"https://github.com/acme/api/pull/56"}]
                """);
            GitHubCodeHostAdapter adapter = adapter(server.baseUrl(), tempDir.resolve("workspace"));
            var preparedWorkspace = adapter.prepareWorkspace(new PrepareWorkspaceCommand(
                UUID.randomUUID(),
                List.of("acme/api"),
                Map.of()
            ));
            Path repositoryDirectory = Path.of(preparedWorkspace.repositories().getFirst().projectRoot());
            Files.writeString(repositoryDirectory.resolve("README.md"), "main\nupdated\n");

            var changes = adapter.publish(new PublishCodeChangesCommand(
                UUID.randomUUID(),
                "SCRUM-56",
                "Blank branch",
                List.of("acme/api"),
                "Summary",
                Map.of("repoChanges", List.of(Map.of(
                    "repository", "acme/api",
                    "headBranch", "   "
                )))
            ));

            assertEquals("panda/scrum-56/acme-api", changes.getFirst().sourceBranch());
            assertEquals("main", git(repositoryDirectory, "rev-parse", "--abbrev-ref", "HEAD").trim());
        }
    }

    @Test
    @DisplayName("Given GitHub rejects pull request lookup when PANDA publishes code then the business failure is surfaced and the workspace is reset")
    void givenGitHubRejectsPullRequestLookup_whenPANDAPublishesCode_thenTheBusinessFailureIsSurfacedAndTheWorkspaceIsReset() throws Exception {
        Path remoteRoot = tempDir.resolve("remotes");
        createRemoteRepository(remoteRoot, "acme/api", false);
        configureGitRewrite(remoteRoot);

        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/repos/acme/api/pulls", 500, "{\"error\":\"server down\"}");
            GitHubCodeHostAdapter adapter = adapter(server.baseUrl(), tempDir.resolve("workspace"));
            var preparedWorkspace = adapter.prepareWorkspace(new PrepareWorkspaceCommand(
                UUID.randomUUID(),
                List.of("acme/api"),
                Map.of()
            ));
            Path repositoryDirectory = Path.of(preparedWorkspace.repositories().getFirst().projectRoot());
            Files.writeString(repositoryDirectory.resolve("README.md"), "main\nupdated\n");

            DomainException exception = assertThrows(DomainException.class, () -> adapter.publish(new PublishCodeChangesCommand(
                UUID.randomUUID(),
                "SCRUM-57",
                "Lookup failure",
                List.of("acme/api"),
                "Summary",
                Map.of("repoChanges", List.of(Map.of(
                    "repository", "acme/api",
                    "headBranch", "review-fixes",
                    "baseBranch", "main"
                )))
            )));

            assertTrue(exception.getMessage().contains("Unable to query pull requests"));
            assertEquals("main", git(repositoryDirectory, "rev-parse", "--abbrev-ref", "HEAD").trim());
        }
    }

    @Test
    @DisplayName("Given GitHub rejects pull request creation when PANDA publishes code then the business failure is surfaced")
    void givenGitHubRejectsPullRequestCreation_whenPANDAPublishesCode_thenTheBusinessFailureIsSurfaced() throws Exception {
        Path remoteRoot = tempDir.resolve("remotes");
        createRemoteRepository(remoteRoot, "acme/api", false);
        configureGitRewrite(remoteRoot);

        try (StubHttpServer server = new StubHttpServer()) {
            server.enqueue("GET", "/repos/acme/api/pulls", 200, "[]");
            server.enqueue("POST", "/repos/acme/api/pulls", 500, "{\"error\":\"server down\"}");
            GitHubCodeHostAdapter adapter = adapter(server.baseUrl(), tempDir.resolve("workspace"));
            var preparedWorkspace = adapter.prepareWorkspace(new PrepareWorkspaceCommand(
                UUID.randomUUID(),
                List.of("acme/api"),
                Map.of()
            ));
            Path repositoryDirectory = Path.of(preparedWorkspace.repositories().getFirst().projectRoot());
            Files.writeString(repositoryDirectory.resolve("README.md"), "main\nupdated\n");

            DomainException exception = assertThrows(DomainException.class, () -> adapter.publish(new PublishCodeChangesCommand(
                UUID.randomUUID(),
                "SCRUM-58",
                "Creation failure",
                List.of("acme/api"),
                "Summary",
                Map.of("repoChanges", List.of(Map.of(
                    "repository", "acme/api",
                    "headBranch", "review-fixes",
                    "baseBranch", "main"
                )))
            )));

            assertTrue(exception.getMessage().contains("Unable to create pull request"));
            assertEquals("main", git(repositoryDirectory, "rev-parse", "--abbrev-ref", "HEAD").trim());
        }
    }

    @Test
    @DisplayName("Given a blank preferred branch when PANDA prepares the workspace then the repository stays on its base branch")
    void givenABlankPreferredBranch_whenPANDAPreparesTheWorkspace_thenTheRepositoryStaysOnItsBaseBranch() throws Exception {
        Path remoteRoot = tempDir.resolve("remotes");
        createRemoteRepository(remoteRoot, "acme/api", true);
        configureGitRewrite(remoteRoot);

        GitHubCodeHostAdapter adapter = adapter("http://127.0.0.1:9", tempDir.resolve("workspace"));

        var preparedWorkspace = adapter.prepareWorkspace(new PrepareWorkspaceCommand(
            UUID.randomUUID(),
            List.of("acme/api"),
            Map.of("acme/api", "   ")
        ));

        Path repositoryDirectory = Path.of(preparedWorkspace.repositories().getFirst().projectRoot());
        assertEquals("main", git(repositoryDirectory, "rev-parse", "--abbrev-ref", "HEAD").trim());
    }

    private GitHubCodeHostAdapter adapter(String apiUrl, Path workspaceRoot) {
        return adapter(apiUrl, workspaceRoot, new GitHubConfig() {
            @Override
            public String apiUrl() {
                return apiUrl;
            }

            @Override
            public String token() {
                return "github-token";
            }

            @Override
            public String defaultBaseBranch() {
                return "main";
            }

            @Override
            public String commitUserName() {
                return "PANDA";
            }

            @Override
            public String commitUserEmail() {
                return "panda@example.com";
            }

            @Override
            public int pollIntervalMinutes() {
                return 1;
            }

            @Override
            public List<Repository> repositories() {
                return List.of(repository("acme/api", "main"));
            }
        });
    }

    private GitHubCodeHostAdapter adapter(String apiUrl, Path workspaceRoot, GitHubConfig config) {
        GitHubCodeHostAdapter adapter = new GitHubCodeHostAdapter();
        ReflectionTestSupport.setField(adapter, "config", config);
        WorkspaceLayoutService workspaceLayoutService = new WorkspaceLayoutService();
        ReflectionTestSupport.setField(workspaceLayoutService, "config", new ApplicationConfig() {
            @Override
            public Workspace workspace() {
                return () -> workspaceRoot.toString();
            }
        });
        ReflectionTestSupport.setField(adapter, "workspaceLayoutService", workspaceLayoutService);
        JsonSupport jsonSupport = new JsonSupport();
        ReflectionTestSupport.setField(jsonSupport, "objectMapper", new ObjectMapper());
        ReflectionTestSupport.setField(adapter, "jsonCodec", jsonSupport);
        return adapter;
    }

    private void configureGitRewrite(Path remoteRoot) throws IOException {
        Path gitConfig = Path.of("build/git-test-config");
        Files.createDirectories(gitConfig.getParent());
        Files.writeString(gitConfig, """
            [url "file://%s/"]
                insteadOf = https://github.com/
            """.formatted(remoteRoot.toAbsolutePath()));
    }

    private void createRemoteRepository(Path remoteRoot, String repository, boolean withReviewBranch) throws Exception {
        Path bareRepository = remoteRoot.resolve(repository + ".git");
        Files.createDirectories(bareRepository.getParent());
        git(tempDir, "init", "--bare", bareRepository.toString());

        Path seed = tempDir.resolve("seed-" + repository.replace('/', '-'));
        Files.createDirectories(seed);
        git(seed, "init");
        git(seed, "checkout", "-b", "main");
        Files.writeString(seed.resolve("README.md"), "main\n");
        git(seed, "add", "README.md");
        git(seed, "-c", "user.name=Seed", "-c", "user.email=seed@example.com", "commit", "-m", "init");
        git(seed, "remote", "add", "origin", bareRepository.toString());
        git(seed, "push", "origin", "main");
        git(bareRepository, "symbolic-ref", "HEAD", "refs/heads/main");

        if (withReviewBranch) {
            git(seed, "checkout", "-b", "review-fixes");
            Files.writeString(seed.resolve("feature.txt"), "feature\n");
            git(seed, "add", "feature.txt");
            git(seed, "-c", "user.name=Seed", "-c", "user.email=seed@example.com", "commit", "-m", "feature");
            git(seed, "push", "origin", "review-fixes");
        }
    }

    private String git(Path workingDirectory, String... args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(command(args));
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Git command failed: " + String.join(" ", args) + "\n" + output);
        }
        return output;
    }

    private GitHubConfig.Repository repository(String name, String baseBranch) {
        return new GitHubConfig.Repository() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String baseBranch() {
                return baseBranch;
            }
        };
    }

    private List<String> command(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }
}
