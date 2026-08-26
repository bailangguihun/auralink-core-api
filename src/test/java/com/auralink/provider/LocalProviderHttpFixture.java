package com.auralink.provider;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class LocalProviderHttpFixture implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<Response> response = new AtomicReference<>(
            new Response(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), 0, Map.of()));
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

    public LocalProviderHttpFixture(String contextPath) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(contextPath, this::handle);
        server.start();
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public void respondJson(int status, String body) {
        response.set(new Response(
                status,
                "application/json",
                body.getBytes(StandardCharsets.UTF_8),
                0,
                Map.of()));
    }

    public void respondJson(int status, String body, Map<String, String> headers) {
        response.set(new Response(
                status,
                "application/json",
                body.getBytes(StandardCharsets.UTF_8),
                0,
                headers));
    }

    public void respondBytes(int status, String contentType, byte[] body) {
        response.set(new Response(status, contentType, body.clone(), 0, Map.of()));
    }

    public void respondAfter(int status, String body, long delayMillis) {
        response.set(new Response(
                status,
                "application/json",
                body.getBytes(StandardCharsets.UTF_8),
                delayMillis,
                Map.of()));
    }

    public List<CapturedRequest> requests() {
        return List.copyOf(requests);
    }

    public int requestCount() {
        return requests.size();
    }

    public CapturedRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        requests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                Map.copyOf(exchange.getRequestHeaders()),
                requestBody));
        Response current = response.get();
        if (current.delayMillis() > 0) {
            try {
                Thread.sleep(current.delayMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        exchange.getResponseHeaders().set("Content-Type", current.contentType());
        current.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        try {
            exchange.sendResponseHeaders(current.status(), current.body().length);
            exchange.getResponseBody().write(current.body());
        } catch (IOException ignored) {
            // Expected when a bounded timeout test closes the client side.
        } finally {
            exchange.close();
        }
    }

    public record CapturedRequest(
            String method,
            URI uri,
            Map<String, List<String>> headers,
            byte[] body) {

        public CapturedRequest {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String firstHeader(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }
    }

    private record Response(
            int status,
            String contentType,
            byte[] body,
            long delayMillis,
            Map<String, String> headers) {
        private Response {
            body = body.clone();
            headers = Map.copyOf(headers);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
