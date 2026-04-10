package io.panda.application.agent.port;

import io.panda.application.command.agent.CancelAgentRunCommand;
import io.panda.application.command.agent.StartAgentRunCommand;

public interface AgentRuntimePort {

    void startRun(StartAgentRunCommand command);

    void cancelRun(CancelAgentRunCommand command);
}
