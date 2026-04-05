package io.devflow.application.port.persistence;

import io.devflow.domain.agent.AgentRunStatus;
import io.devflow.domain.agent.AgentRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRunStore {

    Optional<AgentRun> findRunById(UUID id);

    Optional<AgentRun> findActiveRun();

    Optional<AgentRun> findByWorkflowAndStatuses(UUID workflowId, List<AgentRunStatus> statuses);

    List<AgentRun> findRunsByWorkflow(UUID workflowId);

    AgentRun save(AgentRun run);
}
