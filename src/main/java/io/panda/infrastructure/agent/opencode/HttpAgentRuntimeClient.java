package io.panda.infrastructure.agent.opencode;

import io.panda.application.command.agent.CancelAgentRunCommand;
import io.panda.application.command.agent.StartAgentRunCommand;
import io.panda.application.agent.port.AgentRuntimePort;
import io.panda.application.support.port.JsonCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HttpAgentRuntimeClient implements AgentRuntimePort {

    private static final Logger LOG = Logger.getLogger(HttpAgentRuntimeClient.class);

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Inject
    OpenCodeRuntimeConfig config;

    @Inject
    OpenCodeAgentConfig agentConfig;

    @Inject
    JsonCodec jsonCodec;

    @Override
    public void startRun(StartAgentRunCommand command) {
        LOG.infof(
            "Calling agent runtime to start run %s for workflow %s in phase %s",
            command.agentRunId(),
            command.workflowId(),
            command.phase()
        );
        OpenCodeStartRunRequest payload = OpenCodeStartRunRequest.from(command, agentConfig);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + "/internal/agent-runs"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(90))
            .POST(HttpRequest.BodyPublishers.ofString(jsonCodec.toJson(payload)))
            .build();

        send(request, "Unable to start agent run");
    }

    @Override
    public void cancelRun(CancelAgentRunCommand command) {
        LOG.infof("Calling agent runtime to cancel run %s", command.agentRunId());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + "/internal/agent-runs/" + command.agentRunId() + "/cancel"))
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        send(request, "Unable to cancel agent run");
    }

    private void send(HttpRequest request, String failureMessage) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOG.errorf(
                    "Agent runtime HTTP error: %s — HTTP %d, body=%s",
                    failureMessage, response.statusCode(),
                    response.body().length() > 500 ? response.body().substring(0, 500) : response.body()
                );
                throw new AgentRuntimeException(failureMessage + ": HTTP " + response.statusCode() + " - " + response.body());
            }
            LOG.debugf("Agent runtime call succeeded: %s %s — HTTP %d",
                request.method(), request.uri(), response.statusCode());
        } catch (IOException e) {
            LOG.errorf(e, "Agent runtime connection error: %s — %s %s", failureMessage, request.method(), request.uri());
            throw new AgentRuntimeException(failureMessage, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentRuntimeException(failureMessage, e);
        }
    }
}
