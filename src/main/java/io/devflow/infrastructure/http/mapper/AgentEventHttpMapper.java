package io.devflow.infrastructure.http.mapper;

import io.devflow.application.command.agent.ReceiveAgentEventCommand;
import io.devflow.infrastructure.http.request.AgentEventHttpRequest;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AgentEventHttpMapper {

    public ReceiveAgentEventCommand toCommand(AgentEventHttpRequest request) {
        return new ReceiveAgentEventCommand(
            request.eventId(),
            request.workflowId(),
            request.agentRunId(),
            request.type(),
            request.occurredAt(),
            request.providerRunRef(),
            request.summary(),
            request.blockerType(),
            request.reasonCode(),
            request.requestedFrom(),
            request.resumeTrigger(),
            request.suggestedComment(),
            request.artifacts(),
            request.details()
        );
    }
}
