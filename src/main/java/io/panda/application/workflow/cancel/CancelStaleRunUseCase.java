package io.panda.application.workflow.cancel;

import io.panda.application.agent.port.AgentRuntimePort;
import io.panda.application.command.agent.CancelAgentRunCommand;
import io.panda.application.workflow.port.WorkflowHolder;
import io.panda.domain.model.workflow.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CancelStaleRunUseCase {

    private static final Logger LOG = Logger.getLogger(CancelStaleRunUseCase.class);

    @Inject
    WorkflowHolder workflowHolder;

    @Inject
    AgentRuntimePort agentRuntimePort;

    public void execute(long maxDurationMinutes) {
        Duration maxDuration = Duration.ofMinutes(maxDurationMinutes);
        if (!workflowHolder.isStale(maxDuration)) {
            return;
        }
        Workflow stale = workflowHolder.current();
        if (stale == null) {
            return;
        }
        Duration elapsed = Duration.between(stale.startedAt(), java.time.Instant.now());
        LOG.warnf(
            "Run %s for ticket %s has been active for %d minutes (threshold=%d) in phase %s — cancelling",
            stale.agentRunId(), stale.ticketKey(), elapsed.toMinutes(), maxDurationMinutes, stale.phase()
        );
        try {
            agentRuntimePort.cancelRun(new CancelAgentRunCommand(stale.agentRunId()));
            LOG.infof("Cancel command sent to agent runtime for run %s", stale.agentRunId());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to cancel stale agent run %s — clearing locally", stale.agentRunId());
        }
        workflowHolder.clearIfMatches(stale.agentRunId());
    }
}
