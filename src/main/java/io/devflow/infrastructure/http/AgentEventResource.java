package io.devflow.infrastructure.http;

import io.devflow.application.agent.event.HandleAgentEventUseCase;
import io.devflow.infrastructure.http.mapper.AgentEventHttpMapper;
import io.devflow.infrastructure.http.request.AgentEventHttpRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/internal/agent-events")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AgentEventResource {

    @Inject
    HandleAgentEventUseCase handleAgentEventUseCase;

    @Inject
    AgentEventHttpMapper agentEventHttpMapper;

    @POST
    public Response receive(@Valid AgentEventHttpRequest request) {
        boolean processed = handleAgentEventUseCase.execute(agentEventHttpMapper.toDomain(request));
        return processed ? Response.accepted().build() : Response.status(Response.Status.OK).build();
    }
}
