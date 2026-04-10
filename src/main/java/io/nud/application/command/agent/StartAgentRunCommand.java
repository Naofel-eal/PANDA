package io.nud.application.command.agent;

import io.nud.domain.model.agent.AgentCommandType;
import io.nud.domain.model.workflow.WorkflowPhase;
import java.util.Map;
import java.util.UUID;

public record StartAgentRunCommand(
    UUID commandId,
    UUID workflowId,
    UUID agentRunId,
    AgentCommandType type,
    WorkflowPhase phase,
    String objective,
    Map<String, Object> inputSnapshot
) {

    public static StartAgentRunCommand start(
        UUID commandId,
        UUID workflowId,
        UUID agentRunId,
        WorkflowPhase phase,
        String objective,
        Map<String, Object> inputSnapshot
    ) {
        return new StartAgentRunCommand(
            commandId,
            workflowId,
            agentRunId,
            AgentCommandType.START_RUN,
            phase,
            objective,
            inputSnapshot == null ? Map.of() : Map.copyOf(inputSnapshot)
        );
    }
}
