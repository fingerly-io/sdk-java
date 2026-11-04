package io.fingerly.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A stand-in Fingerly API that records what reached it.
 *
 * <p>A real HTTP server rather than a stubbed client, so redirects, streaming
 * bodies and timeouts behave here as they will in production. The JDK ships
 * one, so the tests stay dependency-free beyond JUnit.
 */
final class FakeApi implements AutoCloseable {

    /** One request as the server saw it. */
    static final class Recorded {
        final String method;
        final String path;
        final String query;
        final com.sun.net.httpserver.Headers headers;
        final byte[] body;
        final int remotePort;

        Recorded(HttpExchange exchange, byte[] body) {
            this.method = exchange.getRequestMethod();
            this.path = exchange.getRequestURI().getRawPath();
            this.query = exchange.getRequestURI().getRawQuery();
            this.headers = exchange.getRequestHeaders();
            this.body = body;
            this.remotePort = exchange.getRemoteAddress().getPort();
        }

        String header(String name) {
            return headers.getFirst(name);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    final List<Recorded> requests = new ArrayList<>();

    private FakeApi(HttpServer server) {
        this.server = server;
    }

    /** Starts a server whose every response is written by {@code handler}. */
    static FakeApi start(Consumer<HttpExchange> handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeApi api = new FakeApi(server);
        server.createContext("/", exchange -> {
            byte[] body = readAll(exchange.getRequestBody());
            api.requests.add(new Recorded(exchange, body));
            try {
                handler.accept(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return api;
    }

    /** Starts a server answering every request with one JSON body and status. */
    static FakeApi json(int status, String body) throws IOException {
        return start(exchange -> respond(exchange, status, body));
    }

    /** Writes a JSON response. */
    static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException cause) {
            throw new IllegalStateException(cause);
        }
    }

    /** The origin to point a client at. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int callCount() {
        return requests.size();
    }

    Recorded first() {
        return requests.get(0);
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            collected.write(chunk, 0, read);
        }
        return collected.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
