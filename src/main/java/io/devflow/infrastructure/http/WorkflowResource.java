package io.devflow.infrastructure.http;

import io.devflow.application.usecase.WorkflowQueryService;
import io.devflow.infrastructure.http.mapper.WorkflowHttpMapper;
import io.devflow.infrastructure.http.response.WorkflowHttpResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

@Path("/api/v1/workflows")
@Produces(MediaType.APPLICATION_JSON)
public class WorkflowResource {

    @Inject
    WorkflowQueryService workflowQueryService;

    @Inject
    WorkflowHttpMapper workflowHttpMapper;

    @GET
    public List<WorkflowHttpResponse> list() {
        return workflowQueryService.list().stream()
            .map(workflowHttpMapper::toResponse)
            .toList();
    }

    @GET
    @Path("/{id}")
    public WorkflowHttpResponse get(@PathParam("id") UUID id) {
        return workflowHttpMapper.toResponse(workflowQueryService.get(id));
    }
}
