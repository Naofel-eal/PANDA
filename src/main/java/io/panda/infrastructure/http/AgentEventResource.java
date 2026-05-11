package io.panda.infrastructure.http;

import io.panda.application.agent.event.HandleAgentEventUseCase;
import io.panda.infrastructure.http.mapper.AgentEventHttpMapper;
import io.panda.infrastructure.http.request.AgentEventHttpRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/internal/agent-events")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AgentEventResource {

    private static final Logger LOG = Logger.getLogger(AgentEventResource.class);

    @Inject
    HandleAgentEventUseCase handleAgentEventUseCase;

    @Inject
    AgentEventHttpMapper agentEventHttpMapper;

    @POST
    public Response receive(@Valid AgentEventHttpRequest request) {
        LOG.infof(
            "Received agent event: eventId=%s, type=%s, agentRunId=%s, workflowId=%s, reasonCode=%s",
            request.eventId(), request.type(), request.agentRunId(), request.workflowId(), request.reasonCode()
        );
        if (request.summary() != null && !request.summary().isBlank()) {
            LOG.infof("Agent event summary: %s", request.summary().length() > 500
                ? request.summary().substring(0, 500) + "..." : request.summary());
        }
        boolean processed = handleAgentEventUseCase.execute(agentEventHttpMapper.toDomain(request));
        LOG.infof("Agent event %s processed=%s", request.eventId(), processed);
        return processed ? Response.accepted().build() : Response.status(Response.Status.OK).build();
    }
}
