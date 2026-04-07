package io.devflow.application.workflow.port;

import io.devflow.domain.model.codehost.CodeChangeRef;
import io.devflow.domain.model.workflow.Workflow;
import io.devflow.domain.model.workflow.WorkflowPhase;
import java.time.Duration;
import java.util.UUID;

public interface WorkflowHolder {

    boolean isBusy();

    boolean isStale(Duration maxDuration);

    Workflow current();

    Workflow start(Workflow workflow);

    void clear();

    void clearIfMatches(UUID agentRunId);

    Workflow replacePhase(WorkflowPhase newPhase, UUID newAgentRunId);

    void addPublishedPR(CodeChangeRef codeChange);
}
