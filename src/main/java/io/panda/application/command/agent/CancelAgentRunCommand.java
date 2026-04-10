package io.panda.application.command.agent;

import java.util.UUID;

public record CancelAgentRunCommand(UUID agentRunId) {
}
