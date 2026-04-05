package io.devflow.application.usecase;

import io.devflow.application.port.persistence.WorkflowStore;
import io.devflow.domain.exception.WorkflowNotFoundException;
import io.devflow.domain.workflow.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class WorkflowQueryService {

    @Inject
    WorkflowStore workflowStore;

    public List<Workflow> list() {
        return workflowStore.listAll();
    }

    public Workflow get(UUID id) {
        return workflowStore.findById(id)
            .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found: " + id));
    }
}
