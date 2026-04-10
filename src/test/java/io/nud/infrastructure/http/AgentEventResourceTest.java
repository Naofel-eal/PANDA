package io.nud.infrastructure.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nud.application.agent.event.HandleAgentEventUseCase;
import io.nud.domain.model.agent.AgentEvent;
import io.nud.domain.model.agent.AgentEventType;
import io.nud.infrastructure.http.mapper.AgentEventHttpMapper;
import io.nud.infrastructure.http.request.AgentEventHttpRequest;
import io.nud.support.ReflectionTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentEventResourceTest {

    @Test
    @DisplayName("Given a processed agent event when NUD receives it then the callback is acknowledged as accepted")
    void givenProcessedAgentEvent_whenNUDReceivesIt_thenCallbackIsAcknowledgedAsAccepted() {
        AgentEventResource resource = new AgentEventResource();
        ReflectionTestSupport.setField(resource, "handleAgentEventUseCase", new HandleAgentEventUseCase() {
            @Override
            public boolean execute(AgentEvent event) {
                return true;
            }
        });
        ReflectionTestSupport.setField(resource, "agentEventHttpMapper", new AgentEventHttpMapper());

        Response response = resource.receive(request());

        assertEquals(202, response.getStatus());
    }

    @Test
    @DisplayName("Given an ignored agent event when NUD receives it then the callback is acknowledged without reprocessing")
    void givenIgnoredAgentEvent_whenNUDReceivesIt_thenCallbackIsAcknowledgedWithoutReprocessing() {
        AgentEventResource resource = new AgentEventResource();
        ReflectionTestSupport.setField(resource, "handleAgentEventUseCase", new HandleAgentEventUseCase() {
            @Override
            public boolean execute(AgentEvent event) {
                return false;
            }
        });
        ReflectionTestSupport.setField(resource, "agentEventHttpMapper", new AgentEventHttpMapper());

        Response response = resource.receive(request());

        assertEquals(200, response.getStatus());
    }

    private AgentEventHttpRequest request() {
        return new AgentEventHttpRequest(
            "event-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            AgentEventType.RUN_STARTED,
            Instant.now(),
            null,
            "Started",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            Map.of()
        );
    }
}
