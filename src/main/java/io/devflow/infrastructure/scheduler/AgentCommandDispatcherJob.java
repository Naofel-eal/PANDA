package io.devflow.infrastructure.scheduler;

import io.devflow.application.command.agent.CancelAgentRunCommand;
import io.devflow.application.command.agent.StartAgentRunCommand;
import io.devflow.application.port.agent.AgentRuntimePort;
import io.devflow.application.port.persistence.AgentRunStore;
import io.devflow.application.port.persistence.OutboxCommandStore;
import io.devflow.application.port.support.JsonCodec;
import io.devflow.domain.agent.AgentCommandType;
import io.devflow.domain.agent.AgentRunStatus;
import io.devflow.domain.agent.AgentRun;
import io.devflow.domain.messaging.OutboxCommand;
import io.devflow.infrastructure.agent.opencode.AgentRuntimeException;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentCommandDispatcherJob {

    private static final Logger LOG = Logger.getLogger(AgentCommandDispatcherJob.class);

    @Inject
    AgentRunStore agentRunStore;

    @Inject
    OutboxCommandStore outboxCommandStore;

    @Inject
    AgentRuntimePort agentRuntimePort;

    @Inject
    JsonCodec jsonCodec;

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void dispatchPendingCommands() {
        if (agentRunStore.findActiveRun().isPresent()) {
            return;
        }

        outboxCommandStore.findNextPending().ifPresent(command -> {
            OutboxCommand currentCommand = command.markProcessing(Instant.now());
            StartAgentRunCommand payload = readPayload(command);
            AgentRun run = payload.agentRunId() == null ? null : agentRunStore.findRunById(payload.agentRunId()).orElse(null);

            try {
                if (currentCommand.commandType() == AgentCommandType.START_RUN) {
                    LOG.infof(
                        "Dispatching START_RUN command %s for agent run %s and workflow %s",
                        currentCommand.id(),
                        payload.agentRunId(),
                        payload.workflowId()
                    );
                    if (run != null) {
                        run = agentRunStore.save(run.markStarting(Instant.now()));
                    }
                    agentRuntimePort.startRun(payload);
                } else if (currentCommand.commandType() == AgentCommandType.CANCEL_RUN) {
                    LOG.infof(
                        "Dispatching CANCEL_RUN command %s for agent run %s",
                        currentCommand.id(),
                        payload.agentRunId()
                    );
                    agentRuntimePort.cancelRun(new CancelAgentRunCommand(payload.agentRunId()));
                    if (run != null) {
                        run = agentRunStore.save(run.markCancelled(Instant.now()));
                    }
                }

                currentCommand = currentCommand.markProcessed(Instant.now());
                outboxCommandStore.save(currentCommand);
                LOG.infof("Processed outbox command %s of type %s", currentCommand.id(), currentCommand.commandType());
            } catch (AgentRuntimeException exception) {
                LOG.errorf(exception, "Unable to dispatch command %s", currentCommand.id());
                currentCommand = currentCommand.markFailed(exception.getMessage(), Instant.now());
                if (run != null && run.status() == AgentRunStatus.STARTING) {
                    run = agentRunStore.save(run.resetToPending(Instant.now()));
                }
                outboxCommandStore.save(currentCommand);
            }
        });
    }

    private StartAgentRunCommand readPayload(OutboxCommand command) {
        return jsonCodec.fromJson(command.payloadJson(), StartAgentRunCommand.class);
    }
}
