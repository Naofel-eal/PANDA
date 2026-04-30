package io.panda.infrastructure.codehost.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GitHubTokenProviderFactory {

    private static final Logger LOG = Logger.getLogger(GitHubTokenProviderFactory.class);

    @Inject
    GitHubConfig config;

    @Produces
    @ApplicationScoped
    GitHubTokenProvider tokenProvider() {
        if (config.appId().isPresent()
            && config.appPrivateKey().isPresent()
            && config.appInstallationId().isPresent()) {
            LOG.info("GitHub authentication mode: GitHub App");
            return new GitHubAppTokenProvider(
                config.appId().get(),
                config.appPrivateKey().get(),
                config.appInstallationId().get(),
                config.apiUrl()
            );
        }
        LOG.info("GitHub authentication mode: Personal Access Token");
        return new GitHubPatTokenProvider(config.token());
    }
}
