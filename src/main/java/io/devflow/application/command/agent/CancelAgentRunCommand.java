package io.devflow.application.command.agent;

import java.util.UUID;

public record CancelAgentRunCommand(UUID agentRunId) {
}
