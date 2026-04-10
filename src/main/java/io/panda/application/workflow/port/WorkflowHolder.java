package io.panda.application.workflow.port;

import io.panda.domain.model.codehost.CodeChangeRef;
import io.panda.domain.model.workflow.Workflow;
import io.panda.domain.model.workflow.WorkflowPhase;
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
