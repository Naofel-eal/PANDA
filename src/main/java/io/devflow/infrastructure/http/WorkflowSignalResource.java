package io.devflow.infrastructure.http;

import io.devflow.infrastructure.http.mapper.WorkflowHttpMapper;
import io.devflow.infrastructure.http.mapper.WorkflowSignalHttpMapper;
import io.devflow.infrastructure.http.request.WorkflowSignalHttpRequest;
import io.devflow.application.usecase.WorkflowSignalService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/workflow-signals")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkflowSignalResource {

    @Inject
    WorkflowSignalService workflowSignalService;

    @Inject
    WorkflowHttpMapper workflowHttpMapper;

    @Inject
    WorkflowSignalHttpMapper workflowSignalHttpMapper;

    @POST
    public Response receive(@Valid WorkflowSignalHttpRequest request) {
        return workflowSignalService.handle(workflowSignalHttpMapper.toCommand(request))
            .map(workflowHttpMapper::toResponse)
            .map(payload -> Response.accepted(payload).build())
            .orElseGet(() -> Response.status(Response.Status.OK).build());
    }
}
