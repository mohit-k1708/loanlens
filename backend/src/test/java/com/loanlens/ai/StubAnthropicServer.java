package com.loanlens.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A LOCAL stand-in for the Anthropic Messages API, used to test the provider code (headers, request body, response
 * parsing, error handling, timeouts) without a real key or network. It is NOT the real service: tests that use it prove
 * how this application behaves, not that a real model answers.
 */
public final class StubAnthropicServer implements AutoCloseable {

    public record Captured(String method, String path, java.util.Map<String, List<String>> headers, String body) {
    }

    private final HttpServer server;
    final List<Captured> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger count = new AtomicInteger();

    /** What the next request should do. */
    volatile int status = 200;
    volatile String responseBody;
    volatile long delayMillis = 0;

    public StubAnthropicServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        respondWithText("{}");
    }

    public static StubAnthropicServer start() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respondWithText(String text) {
        status = 200;
        responseBody = message(text, "end_turn");
    }

    public void respondWithMessage(String text, String stopReason) {
        status = 200;
        responseBody = message(text, stopReason);
    }

    public void respondWithRaw(int httpStatus, String rawBody) {
        status = httpStatus;
        responseBody = rawBody;
    }

    public void respondWithError(int httpStatus, String type, String message) {
        status = httpStatus;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }

    static String message(String text, String stopReason) {
        return "{\"id\":\"msg_stub_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":" + jsonString(text) + "}],"
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50}}";
    }

    static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private void handle(HttpExchange ex) throws IOException {
        count.incrementAndGet();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Captured(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                java.util.Map.copyOf(new java.util.HashMap<>(ex.getRequestHeaders())), body));
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
        } catch (IOException ignored) {
            // the client gave up (timeout test)
        } finally {
            ex.close();
        }
    }

    public int count() {
        return count.get();
    }

    public void setDelayMillis(long millis) {
        delayMillis = millis;
    }

    /** Back to a clean state between tests. */
    public void reset() {
        requests.clear();
        count.set(0);
        delayMillis = 0;
        respondWithText("{}");
    }

    public String lastRequestBody() {
        return requests.get(requests.size() - 1).body();
    }

    public String lastRequestHeader(String name) {
        return requests.get(requests.size() - 1).headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name)).flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
