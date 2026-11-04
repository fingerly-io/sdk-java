package io.fingerly.server.jakarta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fingerly.server.FingerlyProxy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The adapter, driven without a container.
 *
 * <p>The servlet interfaces are stubbed with dynamic proxies rather than a
 * mocking library, so this module's tests need no dependency the SDK does not
 * already have.
 */
class FingerlyProxyFilterTest {

    private static final String PROXY_KEY = "fly_px_development_test";
    private static final String PUBLIC_KEY = "fly_pk_development_test";
    private static final String PATH = "/k7x2qm4a";

    /** Records what the filter wrote. */
    private static final class Written {
        int status;
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
    }

    private static HttpServletRequest request(
            String method, String uri, Map<String, String> headers, byte[] body) {
        return request(method, uri, headers, body, null);
    }

    /** A stub request; with {@code async} non-null it supports startAsync. */
    private static HttpServletRequest request(String method, String uri,
            Map<String, String> headers, byte[] body, AsyncStub async) {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return (HttpServletRequest) Proxy.newProxyInstance(
                FingerlyProxyFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (target, method1, args) -> {
                    switch (method1.getName()) {
                        case "getMethod":
                            return method;
                        case "getRequestURI":
                            return uri;
                        case "getHeaderNames":
                            return Collections.enumeration(headers.keySet());
                        case "getHeaders":
                            String name = (String) args[0];
                            String value = headers.get(name);
                            return Collections.enumeration(
                                    value == null ? List.of() : List.of(value));
                        case "getContentLengthLong":
                            return (long) body.length;
                        case "getInputStream":
                            return servletInput(source);
                        case "getRemoteAddr":
                            return "198.51.100.7";
                        case "isAsyncSupported":
                            return async != null;
                        case "startAsync":
                            return async.context;
                        default:
                            return defaultFor(method1.getReturnType());
                    }
                });
    }

    private static ServletInputStream servletInput(ByteArrayInputStream source) {
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener listener) {}
        };
    }

    private static HttpServletResponse response(Written written) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                FingerlyProxyFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (target, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus":
                            written.status = (int) args[0];
                            return null;
                        case "addHeader":
                            written.headers
                                    .computeIfAbsent((String) args[0], key -> new ArrayList<>())
                                    .add((String) args[1]);
                            return null;
                        case "getOutputStream":
                            return servletOutput(written.body);
                        default:
                            return defaultFor(method.getReturnType());
                    }
                });
    }

    private static ServletOutputStream servletOutput(ByteArrayOutputStream sink) {
        return new ServletOutputStream() {
            @Override
            public void write(int b) {
                sink.write(b);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {}
        };
    }

    /** Dynamic proxies must still answer primitive-returning methods. */
    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }

    private static FingerlyProxy proxy(String upstream) {
        return FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path(PATH)
                .upstream(upstream)
                .clientIp(request -> request.header("CF-Connecting-IP"))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/orders", "/k7x2qm4a/admin"})
    void passesOtherPathsAlongWithTheBodyIntact(String path) throws Exception {
        byte[] body = "{\"order\":1}".getBytes(StandardCharsets.UTF_8);
        HttpServletRequest request =
                request("POST", path, Map.of("Content-Type", "application/json"), body);
        Written written = new Written();

        List<byte[]> seenByApp = new ArrayList<>();
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest passed, ServletResponse response)
                    throws IOException {
                seenByApp.add(((HttpServletRequest) passed).getInputStream().readAllBytes());
            }
        };

        new FingerlyProxyFilter(proxy("https://api.fingerly.io"))
                .doFilter(request, response(written), chain);

        assertEquals(1, seenByApp.size(), "the application never saw the request");
        // The whole point: asking handle() first would have drained this.
        assertEquals("{\"order\":1}", new String(seenByApp.get(0), StandardCharsets.UTF_8));
        assertEquals(0, written.status, "nothing should have been written");
    }

    @Test
    void refusesAWrongMethodOnAnOwnedPathItself() throws Exception {
        HttpServletRequest request = request("GET", PATH, Map.of(), new byte[0]);
        Written written = new Written();
        List<Object> passedAlong = new ArrayList<>();

        new FingerlyProxyFilter(proxy("https://api.fingerly.io"))
                .doFilter(request, response(written),
                        (passed, response) -> passedAlong.add(passed));

        assertTrue(passedAlong.isEmpty(), "an owned path must not reach the application");
        assertEquals(405, written.status);
        assertEquals(List.of("POST"), written.headers.get("Allow"));
    }

    @Test
    void answersEverythingMappedToItWithoutAPath() throws Exception {
        // Mapped to the one path by the container, the proxy needs no path.
        FingerlyProxy unrouted = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .clientIp(request -> request.header("CF-Connecting-IP"))
                .build();
        HttpServletRequest request = request("GET", "/shop/k7x2qm4a", Map.of(), new byte[0]);
        Written written = new Written();
        List<Object> passedAlong = new ArrayList<>();

        new FingerlyProxyFilter(unrouted)
                .doFilter(request, response(written),
                        (passed, response) -> passedAlong.add(passed));

        assertTrue(passedAlong.isEmpty());
        assertEquals(405, written.status);
    }

    @Test
    void refusesAMissingPublicKeyItself() throws Exception {
        HttpServletRequest request = request("POST", PATH, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        Written written = new Written();

        new FingerlyProxyFilter(proxy("https://api.fingerly.io"))
                .doFilter(request, response(written), (passed, response) -> {});

        assertEquals(401, written.status);
        assertEquals(List.of("no-store"), written.headers.get("Cache-Control"));
    }

    @Test
    void writesTheRefusalBody() throws Exception {
        HttpServletRequest request = request("POST", PATH,
                Map.of("X-API-Key", "fly_pk_production_test"), "{}".getBytes(StandardCharsets.UTF_8));
        Written written = new Written();

        new FingerlyProxyFilter(proxy("https://api.fingerly.io"))
                .doFilter(request, response(written), (passed, response) -> {});

        assertEquals(401, written.status);
        assertFalse(written.body.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void readsHeadersWithoutRegardToCase() throws Exception {
        // The filter copies whatever names the container reports; the core
        // matches them case-insensitively.
        HttpServletRequest request = request("POST", PATH,
                Map.of("x-api-key", PUBLIC_KEY, "cf-connecting-ip", "203.0.113.5"),
                "{}".getBytes(StandardCharsets.UTF_8));
        Written written = new Written();

        new FingerlyProxyFilter(proxy("http://127.0.0.1:1"))
                .doFilter(request, response(written), (passed, response) -> {});

        // Authorised, so it tried to forward and the unreachable upstream 502s.
        assertEquals(502, written.status);
    }

    @Test
    void givesTheCallbackThePeerAddress() throws Exception {
        List<String> seen = new ArrayList<>();
        FingerlyProxy proxy = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path(PATH)
                .upstream("http://127.0.0.1:1")
                .clientIp(request -> {
                    seen.add(request.remoteAddress());
                    return request.remoteAddress();
                })
                .build();
        HttpServletRequest request = request("POST", PATH, Map.of("X-API-Key", PUBLIC_KEY),
                "{}".getBytes(StandardCharsets.UTF_8));

        new FingerlyProxyFilter(proxy).doFilter(request, response(new Written()), (p, r) -> {});

        assertEquals(List.of("198.51.100.7"), seen);
    }

    /** A stand-in AsyncContext that records what the filter did with it. */
    private static final class AsyncStub {
        final List<jakarta.servlet.AsyncListener> listeners = new ArrayList<>();
        final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        final jakarta.servlet.AsyncContext context;

        AsyncStub(HttpServletResponse response) {
            context = (jakarta.servlet.AsyncContext) Proxy.newProxyInstance(
                    FingerlyProxyFilterTest.class.getClassLoader(),
                    new Class<?>[] {jakarta.servlet.AsyncContext.class},
                    (target, method, args) -> {
                        switch (method.getName()) {
                            case "addListener":
                                listeners.add((jakarta.servlet.AsyncListener) args[0]);
                                return null;
                            case "start":
                                new Thread((Runnable) args[0]).start();
                                return null;
                            case "complete":
                                completed.countDown();
                                return null;
                            case "getResponse":
                                return response;
                            default:
                                return defaultFor(method.getReturnType());
                        }
                    });
        }
    }

    /** An upstream that waits before answering, or trickles until hung up on. */
    private static com.sun.net.httpserver.HttpServer upstream(boolean trickle,
            java.util.concurrent.CountDownLatch hungUp) throws IOException {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                if (trickle) {
                    exchange.sendResponseHeaders(200, 1 << 20);
                    for (int i = 0; i < 200; i++) {
                        exchange.getResponseBody().write(' ');
                        exchange.getResponseBody().flush();
                        Thread.sleep(25);
                    }
                } else {
                    Thread.sleep(500);
                    byte[] answer = "{\"request_id\":\"r\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, answer.length);
                    exchange.getResponseBody().write(answer);
                }
            } catch (IOException gone) {
                if (hungUp != null) {
                    hungUp.countDown();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static Map<String, String> browser() {
        return Map.of("X-API-Key", PUBLIC_KEY, "CF-Connecting-IP", "203.0.113.5");
    }

    @Test
    void releasesTheContainerThreadWhileTheUpstreamAnswers() throws Exception {
        com.sun.net.httpserver.HttpServer server = upstream(false, null);
        try {
            Written written = new Written();
            HttpServletResponse response = response(written);
            AsyncStub async = new AsyncStub(response);
            HttpServletRequest request = request("POST", PATH, browser(),
                    "{}".getBytes(StandardCharsets.UTF_8), async);

            long started = System.nanoTime();
            new FingerlyProxyFilter(proxy("http://127.0.0.1:" + server.getAddress().getPort()))
                    .doFilter(request, response, (p, r) -> {});
            long heldMillis = (System.nanoTime() - started) / 1_000_000;

            assertTrue(heldMillis < 400, "the container thread was held " + heldMillis + " ms");
            assertTrue(async.completed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(200, written.status);
            assertEquals("{\"request_id\":\"r\"}", written.body.toString(StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void abandonsTheUpstreamCallWhenTheVisitorDisconnects() throws Exception {
        java.util.concurrent.CountDownLatch hungUp = new java.util.concurrent.CountDownLatch(1);
        com.sun.net.httpserver.HttpServer server = upstream(true, hungUp);
        try {
            Written written = new Written();
            HttpServletResponse response = response(written);
            AsyncStub async = new AsyncStub(response);
            HttpServletRequest request = request("POST", PATH, browser(),
                    "{}".getBytes(StandardCharsets.UTF_8), async);

            new FingerlyProxyFilter(proxy("http://127.0.0.1:" + server.getAddress().getPort()))
                    .doFilter(request, response, (p, r) -> {});
            Thread.sleep(200);
            for (jakarta.servlet.AsyncListener listener : async.listeners) {
                listener.onError(null);
            }

            assertTrue(hungUp.await(3, java.util.concurrent.TimeUnit.SECONDS),
                    "the upstream call outlived the visitor");
        } finally {
            server.stop(0);
        }
    }
}
