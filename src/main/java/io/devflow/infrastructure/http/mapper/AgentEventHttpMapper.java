package io.devflow.infrastructure.http.mapper;

import io.devflow.domain.model.agent.AgentEvent;
import io.devflow.infrastructure.http.request.AgentEventHttpRequest;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AgentEventHttpMapper {

    public AgentEvent toDomain(AgentEventHttpRequest request) {
        return new AgentEvent(
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
