package io.devflow.application.port.persistence;

import io.devflow.domain.workflow.WorkflowEvent;
import java.util.List;
import java.util.UUID;

public interface WorkflowEventStore {

    List<WorkflowEvent> findEventsByWorkflowId(UUID workflowId);

    WorkflowEvent save(WorkflowEvent event);
}
