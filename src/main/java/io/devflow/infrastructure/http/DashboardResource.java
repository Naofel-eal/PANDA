package io.devflow.infrastructure.http;

import io.devflow.application.query.TicketDashboardView;
import io.devflow.application.usecase.DashboardQueryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/v1/dashboard")
@Produces(MediaType.APPLICATION_JSON)
public class DashboardResource {

    @Inject
    DashboardQueryService dashboardQueryService;

    @GET
    @Path("/tickets")
    public List<TicketDashboardView> listTickets() {
        return dashboardQueryService.listTickets();
    }
}
