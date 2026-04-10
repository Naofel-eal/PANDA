package io.nud.application.agent.port;

import io.nud.application.command.agent.CancelAgentRunCommand;
import io.nud.application.command.agent.StartAgentRunCommand;

public interface AgentRuntimePort {

    void startRun(StartAgentRunCommand command);

    void cancelRun(CancelAgentRunCommand command);
}
