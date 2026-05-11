package io.panda.application.workflow.port;

import io.panda.domain.model.workflow.Workflow;
import io.panda.domain.model.workflow.WorkflowStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository {

    void save(Workflow workflow);

    Optional<Workflow> findById(UUID workflowId);

    List<Workflow> findByStatus(WorkflowStatus status);
}
