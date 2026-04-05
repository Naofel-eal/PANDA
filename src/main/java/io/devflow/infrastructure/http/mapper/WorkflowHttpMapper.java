package io.devflow.infrastructure.http.mapper;

import io.devflow.domain.workflow.Workflow;
import io.devflow.infrastructure.http.response.WorkflowHttpResponse;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WorkflowHttpMapper {

    public WorkflowHttpResponse toResponse(Workflow workflow) {
        return new WorkflowHttpResponse(
            workflow.id(),
            workflow.workItemSystem(),
            workflow.workItemKey(),
            workflow.phase(),
            workflow.status(),
            workflow.currentBlockerId(),
            workflow.createdAt(),
            workflow.updatedAt()
        );
    }
}
