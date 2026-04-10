package io.nud.infrastructure.agent.opencode;

import io.nud.application.command.agent.StartAgentRunCommand;
import io.nud.domain.model.agent.AgentCommandType;
import io.nud.domain.model.workflow.WorkflowPhase;
import java.util.Map;
import java.util.UUID;

record OpenCodeStartRunRequest(
    UUID commandId,
    UUID workflowId,
    UUID agentRunId,
    AgentCommandType type,
    WorkflowPhase phase,
    String objective,
    Map<String, Object> inputSnapshot,
    OpenCodeExecutionConfig execution
) {

    static OpenCodeStartRunRequest from(StartAgentRunCommand command, OpenCodeAgentConfig config) {
        return new OpenCodeStartRunRequest(
            command.commandId(),
            command.workflowId(),
            command.agentRunId(),
            command.type(),
            command.phase(),
            command.objective(),
            command.inputSnapshot(),
            new OpenCodeExecutionConfig(
                config.model().orElse(null),
                config.smallModel().orElse(null),
                config.openAiApiKey().orElse(null),
                config.anthropicApiKey().orElse(null),
                config.geminiApiKey().orElse(null),
                config.copilotGithubToken().orElse(null)
            )
        );
    }

    record OpenCodeExecutionConfig(
        String model,
        String smallModel,
        String openAiApiKey,
        String anthropicApiKey,
        String geminiApiKey,
        String copilotGithubToken
    ) {
    }
}
