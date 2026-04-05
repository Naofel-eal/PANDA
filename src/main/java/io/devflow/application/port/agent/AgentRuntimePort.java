package io.devflow.application.port.agent;

import io.devflow.application.command.agent.CancelAgentRunCommand;
import io.devflow.application.command.agent.StartAgentRunCommand;

public interface AgentRuntimePort {

    void startRun(StartAgentRunCommand command);

    void cancelRun(CancelAgentRunCommand command);
}
