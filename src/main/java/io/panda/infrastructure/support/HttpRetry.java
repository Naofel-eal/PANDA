package io.panda.infrastructure.support;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jboss.logging.Logger;

public final class HttpRetry {

    private static final Logger LOG = Logger.getLogger(HttpRetry.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_DELAY_MS = 1_000;
    private static final long MAX_DELAY_MS = 5_000;

    private HttpRetry() {}

    public static HttpResponse<String> send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() < 500 && response.statusCode() != 429) {
                    return response;
                }

                if (attempt == MAX_ATTEMPTS) {
                    LOG.warnf("HTTP %d on final attempt (%d/%d): %s %s",
                        response.statusCode(), attempt, MAX_ATTEMPTS, request.method(), request.uri());
                    return response;
                }

                long delay = computeDelay(attempt, response);
                LOG.infof("HTTP %d, retrying in %dms (attempt %d/%d): %s %s",
                    response.statusCode(), delay, attempt, MAX_ATTEMPTS, request.method(), request.uri());
                Thread.sleep(delay);

            } catch (IOException e) {
                lastException = e;
                if (attempt == MAX_ATTEMPTS) {
                    LOG.warnf("Connection failure on final attempt (%d/%d): %s %s — %s",
                        attempt, MAX_ATTEMPTS, request.method(), request.uri(), e.getMessage());
                    throw e;
                }
                long delay = computeDelay(attempt, null);
                LOG.infof("Connection failure, retrying in %dms (attempt %d/%d): %s %s — %s",
                    delay, attempt, MAX_ATTEMPTS, request.method(), request.uri(), e.getMessage());
                Thread.sleep(delay);
            }
        }

        throw lastException != null ? lastException : new IOException("Retry exhausted");
    }

    private static long computeDelay(int attempt, HttpResponse<String> response) {
        if (response != null && response.statusCode() == 429) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            if (retryAfter != null) {
                try {
                    long seconds = Long.parseLong(retryAfter);
                    return Math.min(seconds * 1_000, MAX_DELAY_MS);
                } catch (NumberFormatException ignored) {}
            }
        }
        long delay = INITIAL_DELAY_MS * (1L << (attempt - 1));
        return Math.min(delay, MAX_DELAY_MS);
    }
}
