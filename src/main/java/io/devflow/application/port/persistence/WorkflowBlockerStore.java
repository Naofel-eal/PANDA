package io.devflow.application.port.persistence;

import io.devflow.domain.workflow.WorkflowBlocker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowBlockerStore {

    Optional<WorkflowBlocker> findOpenByWorkflowId(UUID workflowId);

    List<WorkflowBlocker> findBlockersByWorkflowId(UUID workflowId);

    WorkflowBlocker save(WorkflowBlocker blocker);
}
