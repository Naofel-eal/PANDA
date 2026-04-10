package io.nud.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class StubHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, Deque<StubResponse>> responses = new LinkedHashMap<>();
    private final List<RecordedRequest> requests = new ArrayList<>();

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start stub HTTP server", exception);
        }
        server.createContext("/", new DispatchHandler());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void enqueue(String method, String path, int statusCode, String body) {
        responses.computeIfAbsent(key(method, path), ignored -> new ArrayDeque<>())
            .addLast(new StubResponse(statusCode, body));
    }

    public void enqueueExact(String method, String pathAndQuery, int statusCode, String body) {
        responses.computeIfAbsent(key(method, pathAndQuery), ignored -> new ArrayDeque<>())
            .addLast(new StubResponse(statusCode, body));
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public RecordedRequest lastRequest() {
        return requests.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String key(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }

    private final class DispatchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String path = exchange.getRequestURI().getPath();
            String pathAndQuery = query == null ? path : path + "?" + query;
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            requests.add(new RecordedRequest(method, path, query, body, exchange.getRequestHeaders()));

            StubResponse response = poll(key(method, pathAndQuery));
            if (response == null) {
                response = poll(key(method, path));
            }
            if (response == null) {
                response = new StubResponse(404, "{\"error\":\"missing stub\"}");
            }

            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.statusCode(), payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }
    }

    private StubResponse poll(String key) {
        Deque<StubResponse> queue = responses.get(key);
        return queue == null ? null : queue.pollFirst();
    }

    private record StubResponse(int statusCode, String body) {
    }

    public record RecordedRequest(
        String method,
        String path,
        String query,
        String body,
        Headers headers
    ) {
        public String header(String name) {
            return headers.getFirst(name);
        }

        public String pathAndQuery() {
            return query == null ? path : path + "?" + query;
        }
    }
}
