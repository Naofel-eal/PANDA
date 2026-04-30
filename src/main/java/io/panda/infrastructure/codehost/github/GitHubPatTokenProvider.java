package io.panda.infrastructure.codehost.github;

public class GitHubPatTokenProvider implements GitHubTokenProvider {

    private final String token;

    GitHubPatTokenProvider(String token) {
        this.token = token;
    }

    @Override
    public String getToken() {
        return token;
    }
}
