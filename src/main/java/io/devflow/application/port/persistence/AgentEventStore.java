package io.devflow.application.port.persistence;

import io.devflow.domain.agent.AgentEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentEventStore {

    Optional<AgentEvent> findByEventId(String eventId);

    List<AgentEvent> findByAgentRunId(UUID agentRunId);

    AgentEvent save(AgentEvent event);
}
