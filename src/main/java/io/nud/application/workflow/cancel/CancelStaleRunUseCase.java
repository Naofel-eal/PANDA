package io.nud.application.workflow.cancel;

import io.nud.application.agent.port.AgentRuntimePort;
import io.nud.application.command.agent.CancelAgentRunCommand;
import io.nud.application.workflow.port.WorkflowHolder;
import io.nud.domain.model.workflow.Workflow;
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
        LOG.warnf(
            "Run %s for ticket %s has been active for more than %d minutes — cancelling",
            stale.agentRunId(), stale.ticketKey(), maxDurationMinutes
        );
        try {
            agentRuntimePort.cancelRun(new CancelAgentRunCommand(stale.agentRunId()));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to cancel stale agent run %s — clearing locally", stale.agentRunId());
        }
        workflowHolder.clearIfMatches(stale.agentRunId());
    }
}
