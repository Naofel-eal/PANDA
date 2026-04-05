package io.devflow.domain.validation;

public record DeploymentInfo(
    String environment,
    String branch,
    String url
) {
}
