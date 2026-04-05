package io.devflow.application.port.persistence;

import io.devflow.domain.workflow.Workflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStore {

    Optional<Workflow> findById(UUID id);

    Optional<Workflow> findByWorkItem(String system, String key);

    List<Workflow> listAll();

    List<Workflow> findWaitingSystemOrdered();

    Workflow save(Workflow workflow);
}
