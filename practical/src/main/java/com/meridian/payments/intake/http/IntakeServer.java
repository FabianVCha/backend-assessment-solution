package com.meridian.payments.intake.http;

import com.meridian.payments.intake.ops.OperationsRouter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The transport adapter: a JDK {@link HttpServer} that hands requests to {@link WebhookEndpoint}.
 *
 * <p>Deliberately thin. It reads the bytes, the headers and the path and writes back what the
 * endpoint decided; every status code, every body and every check lives in the endpoint, which is
 * where the contract is tested. Swapping this for a servlet or a framework changes this file and
 * nothing else.
 *
 * <p>The body is read with a cap: the endpoint answers 413 for anything over 64 KiB, but the adapter
 * must not buffer a gigabyte to let it say so.
 */
public final class IntakeServer implements AutoCloseable {

    private static final int READ_LIMIT = 64 * 1024 + 1;

    private final HttpServer server;
    private final ExecutorService executor;

    private IntakeServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static IntakeServer start(WebhookEndpoint endpoint, int port) throws IOException {
        return start(endpoint, port, null);
    }

    public static IntakeServer start(WebhookEndpoint endpoint, int port, OperationsRouter operations)
            throws IOException {
        Objects.requireNonNull(endpoint, "endpoint");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, endpoint));
        if (operations != null) {
            server.createContext(OperationsRouter.ROOT + "/", operations);
        }
        server.start();
        return new IntakeServer(server, executor);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void handle(HttpExchange exchange, WebhookEndpoint endpoint) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, HttpResponse.empty(405));
                return;
            }
            HttpResponse response = endpoint.handle(new HttpRequest(
                    exchange.getRequestURI().getPath(),
                    headers(exchange),
                    readBody(exchange.getRequestBody())));
            respond(exchange, response);
        }
    }

    private static byte[] readBody(InputStream body) throws IOException {
        byte[] read = body.readNBytes(READ_LIMIT);
        return read.length > READ_LIMIT - 1 ? new byte[READ_LIMIT] : read;
    }

    private static Map<String, String> headers(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }

    private static void respond(HttpExchange exchange, HttpResponse response) throws IOException {
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        if (response.body().isEmpty()) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        byte[] body = response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
    }
}
