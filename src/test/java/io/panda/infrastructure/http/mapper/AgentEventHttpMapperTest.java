package io.panda.infrastructure.http.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.panda.domain.model.agent.AgentEventType;
import io.panda.domain.model.workflow.BlockerType;
import io.panda.infrastructure.http.request.AgentEventHttpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentEventHttpMapperTest {

    @Test
    @DisplayName("Given an HTTP callback from the agent when it is mapped then the business event preserves the workflow meaning")
    void givenHttpCallbackFromAgent_whenItIsMapped_thenBusinessEventPreservesWorkflowMeaning() {
        AgentEventHttpRequest request = new AgentEventHttpRequest(
            "event-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            AgentEventType.INPUT_REQUIRED,
            Instant.parse("2026-04-09T10:00:00Z"),
            "provider:1",
            "Need clarification",
            BlockerType.MISSING_TICKET_INFORMATION,
            "missing info",
            null,
            null,
            "Please add details",
            Map.of("repoChanges", List.of()),
            Map.of("missingInformation", List.of("description"))
        );

        var event = new AgentEventHttpMapper().toDomain(request);

        assertEquals(request.eventId(), event.eventId());
        assertEquals(request.workflowId(), event.workflowId());
        assertEquals(request.reasonCode(), event.reasonCode());
        assertEquals(request.artifacts(), event.artifacts());
    }
}
