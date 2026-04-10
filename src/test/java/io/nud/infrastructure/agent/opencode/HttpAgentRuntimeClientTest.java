package io.nud.infrastructure.agent.opencode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.nud.application.command.agent.CancelAgentRunCommand;
import io.nud.application.command.agent.StartAgentRunCommand;
import io.nud.domain.model.workflow.WorkflowPhase;
import io.nud.infrastructure.support.JsonSupport;
import io.nud.support.ReflectionTestSupport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpAgentRuntimeClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Given an implementation request when NUD starts the agent then the runtime receives the expected JSON payload")
    void givenImplementationRequest_whenNUDStartsTheAgent_thenTheRuntimeReceivesTheExpectedJsonPayload() throws Exception {
        RecordingHandler handler = startServer(202, "{}");
        HttpAgentRuntimeClient client = client(serverBaseUrl());

        client.startRun(StartAgentRunCommand.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            WorkflowPhase.IMPLEMENTATION,
            "Implement SCRUM-13",
            java.util.Map.of("workItemKey", "SCRUM-13")
        ));

        assertEquals("POST", handler.method);
        assertEquals("/internal/agent-runs", handler.path);
        assertTrue(handler.body.contains("\"objective\":\"Implement SCRUM-13\""));
        assertTrue(handler.body.contains("\"phase\":\"IMPLEMENTATION\""));
        assertTrue(handler.body.contains("\"model\":\"gpt-5.4\""));
    }

    @Test
    @DisplayName("Given a stale run when NUD cancels it then the runtime receives the dedicated cancel endpoint call")
    void givenAStaleRun_whenNUDCancelsIt_thenTheRuntimeReceivesTheDedicatedCancelEndpointCall() throws Exception {
        RecordingHandler handler = startServer(202, "{}");
        HttpAgentRuntimeClient client = client(serverBaseUrl());
        UUID agentRunId = UUID.randomUUID();

        client.cancelRun(new CancelAgentRunCommand(agentRunId));

        assertEquals("POST", handler.method);
        assertEquals("/internal/agent-runs/" + agentRunId + "/cancel", handler.path);
        assertEquals("", handler.body);
    }

    @Test
    @DisplayName("Given a runtime refusal when NUD starts the agent then the failure is surfaced with the HTTP response details")
    void givenARuntimeRefusal_whenNUDStartsTheAgent_thenTheFailureIsSurfacedWithTheHttpResponseDetails() throws Exception {
        startServer(400, "{\"error\":\"bad request\"}");
        HttpAgentRuntimeClient client = client(serverBaseUrl());

        AgentRuntimeException exception = assertThrows(AgentRuntimeException.class, () -> client.startRun(
            StartAgentRunCommand.start(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WorkflowPhase.INFORMATION_COLLECTION,
                "Understand SCRUM-14",
                java.util.Map.of()
            )
        ));

        assertTrue(exception.getMessage().contains("Unable to start agent run"));
        assertTrue(exception.getMessage().contains("HTTP 400"));
    }

    @Test
    @DisplayName("Given the agent runtime is unreachable when NUD starts a run then the transport failure is surfaced")
    void givenTheAgentRuntimeIsUnreachable_whenNUDStartsARun_thenTheTransportFailureIsSurfaced() throws Exception {
        HttpAgentRuntimeClient client = client("http://agent.example");
        HttpClient httpClient = mock(HttpClient.class);
        ReflectionTestSupport.setField(client, "client", httpClient);
        doThrow(new IOException("network down")).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        AgentRuntimeException exception = assertThrows(AgentRuntimeException.class, () -> client.startRun(
            StartAgentRunCommand.start(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WorkflowPhase.IMPLEMENTATION,
                "Implement SCRUM-15",
                java.util.Map.of()
            )
        ));

        assertTrue(exception.getMessage().contains("Unable to start agent run"));
        assertTrue(exception.getCause() instanceof IOException);
    }

    @Test
    @DisplayName("Given cancellation is interrupted when NUD stops a stale run then the interruption is surfaced and preserved")
    void givenCancellationIsInterrupted_whenNUDStopsAStaleRun_thenTheInterruptionIsSurfacedAndPreserved() throws Exception {
        HttpAgentRuntimeClient client = client("http://agent.example");
        HttpClient httpClient = mock(HttpClient.class);
        ReflectionTestSupport.setField(client, "client", httpClient);
        doThrow(new InterruptedException("stop")).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        AgentRuntimeException exception = assertThrows(AgentRuntimeException.class, () -> client.cancelRun(new CancelAgentRunCommand(UUID.randomUUID())));

        assertTrue(exception.getMessage().contains("Unable to cancel agent run"));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    private HttpAgentRuntimeClient client(String baseUrl) {
        HttpAgentRuntimeClient client = new HttpAgentRuntimeClient();
        ReflectionTestSupport.setField(client, "config", new OpenCodeRuntimeConfig() {
            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public int hardTimeoutMinutes() {
                return 15;
            }

            @Override
            public int staleTimeoutBufferMinutes() {
                return 5;
            }
        });
        ReflectionTestSupport.setField(client, "agentConfig", new OpenCodeAgentConfig() {
            @Override
            public Optional<String> model() {
                return Optional.of("gpt-5.4");
            }

            @Override
            public Optional<String> smallModel() {
                return Optional.empty();
            }

            @Override
            public Optional<String> openAiApiKey() {
                return Optional.empty();
            }

            @Override
            public Optional<String> anthropicApiKey() {
                return Optional.empty();
            }

            @Override
            public Optional<String> geminiApiKey() {
                return Optional.empty();
            }

            @Override
            public Optional<String> copilotGithubToken() {
                return Optional.of("gh-token");
            }
        });
        JsonSupport jsonSupport = new JsonSupport();
        ReflectionTestSupport.setField(jsonSupport, "objectMapper", new ObjectMapper());
        ReflectionTestSupport.setField(client, "jsonCodec", jsonSupport);
        return client;
    }

    private RecordingHandler startServer(int statusCode, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        RecordingHandler handler = new RecordingHandler(statusCode, responseBody);
        server.createContext("/internal/agent-runs", handler);
        server.start();
        return handler;
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final class RecordingHandler implements HttpHandler {
        private final int statusCode;
        private final String responseBody;
        private String method;
        private String path;
        private String body;

        private RecordingHandler(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }
    }
}
