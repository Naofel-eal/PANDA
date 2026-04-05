package io.devflow.infrastructure.codehost.github;

import io.devflow.application.command.codehost.PublishCodeChangesCommand;
import io.devflow.application.command.workspace.PrepareWorkspaceCommand;
import io.devflow.application.port.codehost.CodeHostPort;
import io.devflow.application.port.support.JsonCodec;
import io.devflow.application.service.WorkspaceLayoutService;
import io.devflow.domain.exception.DomainException;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.workspace.PreparedWorkspace;
import io.devflow.domain.workspace.RepositoryWorkspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GitHubCodeHostAdapter implements CodeHostPort {

    private static final Logger LOG = Logger.getLogger(GitHubCodeHostAdapter.class);
    public static final String BRANCH_PREFIX = "devflow/";
    public static final String PULL_REQUEST_EXTERNAL_ID_SEPARATOR = "#";

    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration GITHUB_API_TIMEOUT = Duration.ofSeconds(15);
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String GITHUB_ACCEPT_HEADER = "application/vnd.github+json";
    private static final String GITHUB_HOST = "github.com";
    private static final String GIT_AUTH_USERNAME = "x-access-token";
    private static final String GIT_EXTRA_HEADER_NAME = "http.extraHeader";
    private static final String GIT_BASIC_AUTH_SCHEME = "AUTHORIZATION: basic ";
    private static final String REPOSITORY_ENV_PREFIX = "DEVFLOW_GITHUB_REPOSITORIES_";
    private static final String REPOSITORY_NAME_ENV_SUFFIX = "_NAME";
    private static final String REPOSITORY_BASE_BRANCH_ENV_SUFFIX = "_BASE_BRANCH";

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT)
        .build();

    @Inject
    GitHubConfig config;

    @Inject
    WorkspaceLayoutService workspaceLayoutService;

    @Inject
    JsonCodec jsonCodec;

    @Override
    public List<CodeChangeRef> publish(PublishCodeChangesCommand command) {
        if (command.repositories().isEmpty()) {
            throw new DomainException("Missing GitHub repository mapping");
        }

        LOG.infof(
            "Publishing code changes for workflow %s across %d repositories",
            command.workflowId(),
            command.repositories().size()
        );
        Map<String, RepoPublishInstructions> instructionsByRepository = extractRepoInstructions(command);
        List<CodeChangeRef> publishedCodeChanges = new ArrayList<>();
        for (String repository : command.repositories()) {
            Path repoPath = workspaceLayoutService.repositoryDirectory(command.workflowId(), repository);
            if (!Files.exists(repoPath.resolve(".git"))) {
                throw new DomainException("Repository workspace is missing for " + repository);
            }

            String status = runGit(repoPath, List.of("status", "--porcelain"));
            if (status.isBlank()) {
                LOG.infof("No local changes detected for repository %s, resetting to base branch", repository);
                cleanupWorkspace(repoPath, resolveBaseBranch(repository));
                continue;
            }

            RepoPublishInstructions instructions = instructionsByRepository.getOrDefault(
                repository,
                buildDefaultInstructions(command, repository)
            );
            String branchName = enforceDevflowPrefix(instructions.branchName());
            try {
                LOG.infof(
                    "Publishing repository %s on branch %s targeting %s",
                    repository,
                    branchName,
                    instructions.baseBranch()
                );
                runGit(repoPath, List.of("checkout", "-B", branchName));
                runGit(repoPath, List.of("add", "-A"));

                String postAddStatus = runGit(repoPath, List.of("status", "--porcelain"));
                if (postAddStatus.isBlank()) {
                    continue;
                }

                runGit(repoPath, List.of("commit", "-m", instructions.commitMessage()));
                String remoteUrl = buildRemoteUrl(repository);
                runGit(repoPath, List.of("push", "--force", "--set-upstream", remoteUrl, "HEAD:refs/heads/" + branchName));
                LOG.infof("Pushed branch %s for repository %s", branchName, repository);

                Map<String, Object> existingPr = findOpenPullRequest(repository, branchName);
                if (existingPr != null) {
                    Number number = (Number) existingPr.get("number");
                    String htmlUrl = (String) existingPr.get("html_url");
                    publishedCodeChanges.add(new CodeChangeRef(
                        GitHubSystem.ID,
                        repository + PULL_REQUEST_EXTERNAL_ID_SEPARATOR + number.longValue(),
                        repository,
                        htmlUrl,
                        branchName,
                        instructions.baseBranch()
                    ));
                    LOG.infof(
                        "Reused existing pull request %s for repository %s",
                        repository + PULL_REQUEST_EXTERNAL_ID_SEPARATOR + number.longValue(),
                        repository
                    );
                    continue;
                }

                Map<String, Object> createdPr = createPullRequest(
                    repository,
                    instructions.prTitle(),
                    branchName,
                    instructions.baseBranch(),
                    instructions.prBody()
                );
                Number number = (Number) createdPr.get("number");
                String htmlUrl = (String) createdPr.get("html_url");
                publishedCodeChanges.add(new CodeChangeRef(
                    GitHubSystem.ID,
                    repository + PULL_REQUEST_EXTERNAL_ID_SEPARATOR + number.longValue(),
                    repository,
                    htmlUrl,
                    branchName,
                    instructions.baseBranch()
                ));
                LOG.infof(
                    "Created pull request %s for repository %s",
                    repository + PULL_REQUEST_EXTERNAL_ID_SEPARATOR + number.longValue(),
                    repository
                );
            } finally {
                cleanupWorkspace(repoPath, instructions.baseBranch());
            }
        }

        if (publishedCodeChanges.isEmpty()) {
            throw new DomainException("No local changes were detected in the workflow repositories");
        }

        return publishedCodeChanges;
    }

    @Override
    public List<String> configuredRepositories() {
        return configuredRepositoryDefinitions().stream()
            .map(GitHubRepositoryDefinition::name)
            .filter(current -> current != null && !current.isBlank())
            .distinct()
            .toList();
    }

    @Override
    public PreparedWorkspace prepareWorkspace(PrepareWorkspaceCommand command) {
        List<String> configuredRepositories = configuredRepositories();
        List<String> repositories = command.repositories() == null || command.repositories().isEmpty()
            ? configuredRepositories
            : command.repositories().stream()
                .filter(configuredRepositories::contains)
                .distinct()
                .toList();
        if (repositories.isEmpty()) {
            throw new DomainException("No accessible GitHub repository configured for this workflow");
        }

        LOG.infof(
            "Preparing shared workspace for workflow %s with %d repositories",
            command.workflowId(),
            repositories.size()
        );
        List<RepositoryWorkspace> preparedRepositories = new ArrayList<>();
        for (String repository : repositories) {
            Path workspace = workspaceLayoutService.repositoryDirectory(command.workflowId(), repository);
            String remoteUrl = buildRemoteUrl(repository);

            ensureWorkspaceParentExists(workspace);

            if (Files.exists(workspace.resolve(".git"))) {
                LOG.infof("Refreshing repository %s in %s", repository, workspace);
                runGit(workspace, List.of("remote", "set-url", "origin", remoteUrl));
                runGit(workspace, List.of("fetch", "origin"));
            } else {
                LOG.infof("Cloning repository %s into %s", repository, workspace);
                runGit(workspace, List.of("clone", remoteUrl, "."));
            }

            String branch = command.preferredBranches().getOrDefault(repository, resolveBaseBranch(repository));
            LOG.infof("Checking out repository %s on branch %s", repository, branch);
            checkoutWorkspace(workspace, branch, resolveBaseBranch(repository));
            preparedRepositories.add(new RepositoryWorkspace(repository, workspace.toString()));
        }

        return new PreparedWorkspace(
            workspaceLayoutService.workspaceRoot().toString(),
            preparedRepositories
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, RepoPublishInstructions> extractRepoInstructions(PublishCodeChangesCommand command) {
        Map<String, Object> artifacts = command.artifacts();
        Object repoChanges = artifacts.get("repoChanges");
        if (!(repoChanges instanceof List<?> list)) {
            return Map.of();
        }

        Map<String, RepoPublishInstructions> instructions = new LinkedHashMap<>();
        for (Object current : list) {
            if (!(current instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> entry = rawMap.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                element -> String.valueOf(element.getKey()),
                Map.Entry::getValue,
                (left, right) -> right,
                LinkedHashMap::new
            ));
            Object repository = entry.get("repository");
            if (!(repository instanceof String repositorySlug) || repositorySlug.isBlank()) {
                continue;
            }
            instructions.put(repositorySlug, buildInstructions(command, repositorySlug, entry));
        }
        return instructions;
    }

    private RepoPublishInstructions buildDefaultInstructions(PublishCodeChangesCommand command, String repository) {
        return buildInstructions(command, repository, Map.of());
    }

    private RepoPublishInstructions buildInstructions(
        PublishCodeChangesCommand command,
        String repository,
        Map<String, Object> artifacts
    ) {
        String repositorySegment = sanitizeRepositorySegment(repository);
        String branchName = stringArtifact(
            artifacts,
            "headBranch",
            BRANCH_PREFIX + sanitizeBranchSegment(command.workItemKey()) + "/" + repositorySegment
        );
        String baseBranch = stringArtifact(artifacts, "baseBranch", resolveBaseBranch(repository));
        String summary = stringArtifact(artifacts, "summary", command.summary());
        String defaultTitle = defaultConventionalTitle(command.workItemTitle(), summary);
        String commitMessage = stringArtifact(
            artifacts,
            "commitMessage",
            defaultTitle
        );
        String prTitle = appendTicketSuffix(stringArtifact(
            artifacts,
            "prTitle",
            commitMessage
        ), command.workItemKey());
        String prBody = stringArtifact(artifacts, "prBody", summary == null ? "" : summary);
        return new RepoPublishInstructions(repository, branchName, baseBranch, commitMessage, prTitle, prBody);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findOpenPullRequest(String repository, String branchName) {
        String[] ownerRepo = parseRepository(repository);
        HttpRequest request = baseRequest("/repos/" + ownerRepo[0] + "/" + ownerRepo[1]
                + "/pulls?state=open&head=" + ownerRepo[0] + ":" + branchName)
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new DomainException("Unable to query pull requests: HTTP " + response.statusCode() + " - " + response.body());
            }
            List<Map<String, Object>> pulls = jsonCodec.fromJson(response.body(), List.class);
            return pulls.isEmpty() ? null : pulls.getFirst();
        } catch (IOException exception) {
            throw new DomainException("Unable to query GitHub pull requests", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DomainException("Interrupted while querying GitHub pull requests", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createPullRequest(String repository, String title, String head, String base, String body) {
        String[] ownerRepo = parseRepository(repository);
        String payload = jsonCodec.toJson(Map.of(
            "title", title,
            "head", head,
            "base", base,
            "body", body
        ));

        HttpRequest request = baseRequest("/repos/" + ownerRepo[0] + "/" + ownerRepo[1] + "/pulls")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new DomainException("Unable to create pull request: HTTP " + response.statusCode() + " - " + response.body());
            }
            return jsonCodec.fromJson(response.body(), Map.class);
        } catch (IOException exception) {
            throw new DomainException("Unable to create GitHub pull request", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DomainException("Interrupted while creating GitHub pull request", exception);
        }
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(config.apiUrl() + path))
            .header(HEADER_AUTHORIZATION, "Bearer " + config.token())
            .header(HEADER_ACCEPT, GITHUB_ACCEPT_HEADER)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .timeout(GITHUB_API_TIMEOUT);
    }

    private String[] parseRepository(String repository) {
        String[] parts = repository.split("/", 2);
        if (parts.length != 2) {
            throw new DomainException("Invalid GitHub repository slug: " + repository);
        }
        return parts;
    }

    private void ensureWorkspaceParentExists(Path workspace) {
        try {
            Files.createDirectories(workspace);
        } catch (IOException exception) {
            throw new DomainException("Unable to create repository workspace", exception);
        }
    }

    private void checkoutWorkspace(Path workspace, String branch, String baseBranch) {
        String targetBranch = branch == null || branch.isBlank() ? baseBranch : branch;
        runGit(workspace, List.of("fetch", "origin"));
        if (hasRemoteBranch(workspace, targetBranch)) {
            runGit(workspace, List.of("checkout", "-B", targetBranch, "origin/" + targetBranch));
            runGit(workspace, List.of("reset", "--hard", "origin/" + targetBranch));
        } else {
            runGit(workspace, List.of("checkout", "-B", targetBranch, "origin/" + baseBranch));
        }
        runGit(workspace, List.of("clean", "-fd"));
    }

    private void cleanupWorkspace(Path workspace, String baseBranch) {
        LOG.infof("Resetting workspace %s back to base branch %s", workspace, baseBranch);
        runGit(workspace, List.of("fetch", "origin"));
        runGit(workspace, List.of("checkout", "-B", baseBranch, "origin/" + baseBranch));
        runGit(workspace, List.of("reset", "--hard", "origin/" + baseBranch));
        runGit(workspace, List.of("clean", "-fd"));
    }

    private boolean hasRemoteBranch(Path workspace, String branch) {
        try {
            runGit(workspace, List.of("show-ref", "--verify", "--quiet", "refs/remotes/origin/" + branch));
            return true;
        } catch (DomainException exception) {
            return false;
        }
    }

    private String runGit(Path workingDirectory, List<String> args) {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(buildCommand(args));
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);

        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new DomainException("Git command failed: " + String.join(" ", args) + "\n" + output);
            }
            return output;
        } catch (IOException exception) {
            throw new DomainException("Unable to execute git command", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DomainException("Interrupted while executing git command", exception);
        }
    }

    private List<String> buildCommand(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add(GIT_EXTRA_HEADER_NAME + "=" + buildGitAuthorizationHeader());
        if (!args.isEmpty() && "commit".equals(args.getFirst())) {
            command.add("-c");
            command.add("user.name=" + config.commitUserName());
            command.add("-c");
            command.add("user.email=" + config.commitUserEmail());
        }
        command.addAll(args);
        return command;
    }

    private String stringArtifact(Map<String, Object> artifacts, String key, String fallback) {
        Object value = artifacts.get(key);
        return value instanceof String current && !current.isBlank() ? current : fallback;
    }

    private String defaultSummary(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String defaultConventionalTitle(String workItemTitle, String summary) {
        String subject = defaultSummary(workItemTitle, summary);
        subject = defaultSummary(subject, "implementation update").trim();
        return "chore(DEVFLOW): " + subject;
    }

    private String appendTicketSuffix(String title, String workItemKey) {
        if (workItemKey == null || workItemKey.isBlank()) {
            return title;
        }
        String suffix = " [" + workItemKey.trim() + "]";
        return title != null && title.endsWith(suffix) ? title : defaultSummary(title, "").trim() + suffix;
    }

    private String enforceDevflowPrefix(String branchName) {
        String trimmed = branchName == null ? "" : branchName.trim();
        if (trimmed.isBlank()) {
            throw new DomainException("Git branch name must not be blank");
        }
        if (trimmed.startsWith(BRANCH_PREFIX)) {
            return trimmed;
        }
        return BRANCH_PREFIX + trimmed.replaceFirst("^/+", "");
    }

    private String sanitizeBranchSegment(String value) {
        return value == null
            ? "work-item"
            : value.toLowerCase().replaceAll("[^a-z0-9._/-]+", "-").replaceAll("/+", "-");
    }

    private String sanitizeRepositorySegment(String repository) {
        return sanitizeBranchSegment(repository);
    }

    private String buildRemoteUrl(String repository) {
        return "https://" + GITHUB_HOST + "/" + repository + ".git";
    }

    private String buildGitAuthorizationHeader() {
        String credentials = GIT_AUTH_USERNAME + ":" + config.token();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return GIT_BASIC_AUTH_SCHEME + encoded;
    }

    private String resolveBaseBranch(String repository) {
        for (GitHubRepositoryDefinition current : configuredRepositoryDefinitions()) {
            if (repository.equals(current.name())) {
                return current.baseBranch();
            }
        }
        return config.defaultBaseBranch();
    }

    private List<GitHubRepositoryDefinition> configuredRepositoryDefinitions() {
        List<GitHubRepositoryDefinition> mappedRepositories = mappedRepositoryDefinitions();
        if (!mappedRepositories.isEmpty()) {
            return mappedRepositories;
        }

        List<GitHubRepositoryDefinition> environmentRepositories = environmentRepositoryDefinitions();
        if (!environmentRepositories.isEmpty()) {
            LOG.infof("Loaded %d GitHub repositories from environment fallback", environmentRepositories.size());
        }
        return environmentRepositories;
    }

    private List<GitHubRepositoryDefinition> mappedRepositoryDefinitions() {
        if (config.repositories() == null) {
            return List.of();
        }
        return config.repositories().stream()
            .map(current -> new GitHubRepositoryDefinition(current.name(), current.baseBranch()))
            .filter(current -> current.name() != null && !current.name().isBlank())
            .toList();
    }

    private List<GitHubRepositoryDefinition> environmentRepositoryDefinitions() {
        Map<Integer, String> repositoryNames = new LinkedHashMap<>();
        Map<Integer, String> baseBranches = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(REPOSITORY_ENV_PREFIX)) {
                continue;
            }
            String suffix = key.substring(REPOSITORY_ENV_PREFIX.length());
            int separatorIndex = suffix.indexOf('_');
            if (separatorIndex <= 0) {
                continue;
            }

            Integer repositoryIndex = parseRepositoryIndex(suffix.substring(0, separatorIndex));
            if (repositoryIndex == null) {
                continue;
            }

            if (suffix.endsWith(REPOSITORY_NAME_ENV_SUFFIX)) {
                repositoryNames.put(repositoryIndex, entry.getValue());
            } else if (suffix.endsWith(REPOSITORY_BASE_BRANCH_ENV_SUFFIX)) {
                baseBranches.put(repositoryIndex, entry.getValue());
            }
        }

        return repositoryNames.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new GitHubRepositoryDefinition(
                entry.getValue(),
                baseBranches.getOrDefault(entry.getKey(), config.defaultBaseBranch())
            ))
            .filter(current -> current.name() != null && !current.name().isBlank())
            .toList();
    }

    private Integer parseRepositoryIndex(String rawValue) {
        try {
            return Integer.valueOf(rawValue);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record RepoPublishInstructions(
        String repository,
        String branchName,
        String baseBranch,
        String commitMessage,
        String prTitle,
        String prBody
    ) {
    }

    private record GitHubRepositoryDefinition(String name, String baseBranch) {
    }
}
