package io.fingerly.server.jakarta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.fingerly.server.AgentSighting;
import io.fingerly.server.AgentSightings;
import io.fingerly.server.FingerlyClient;
import io.fingerly.server.ProxyRequest;
import io.fingerly.server.VisitorKind;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agent sightings filter, driven without a container against a local
 * stand-in for the API.
 */
class FingerlyAgentSightingsFilterTest {

    private static final String GPTBOT = "Mozilla/5.0 (compatible; GPTBot/1.4; +https://openai.com/gptbot)";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer api;
    private final List<JsonNode> received = Collections.synchronizedList(new ArrayList<>());
    /** While set and not counted down, the API holds every report it receives. */
    private volatile CountDownLatch hold;
    private AgentSightings sightings;

    @BeforeEach
    void start() throws IOException {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            received.add(body);
            CountDownLatch gate = hold;
            try {
                if (gate != null) {
                    gate.await(30, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            StringBuilder answer = new StringBuilder("{\"rules_version\":\"r1\",\"items\":[");
            for (int i = 0; i < body.path("items").size(); i++) {
                answer.append(i == 0 ? "" : ",")
                        .append("{\"ttl\":600,\"visitor_kind\":\"ai_bot\",\"agent\":\"GPTBot\"}");
            }
            byte[] bytes = answer.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        api.start();
        FingerlyClient client = FingerlyClient.builder()
                .secretKey("fly_sk_development_test")
                .allowInsecureLoopback(true)
                .endpoint("http://127.0.0.1:" + api.getAddress().getPort())
                .build();
        sightings = AgentSightings.builder()
                .client(client)
                .clientIp(ProxyRequest::remoteAddress)
                .build();
    }

    @AfterEach
    void stop() {
        CountDownLatch gate = hold;
        if (gate != null) {
            gate.countDown();
        }
        sightings.close();
        api.stop(0);
    }

    /** A request stub; a non-null {@code async} makes it asynchronous. */
    private static HttpServletRequest request(String uri, String userAgent,
            AtomicReference<AsyncListener> async) {
        return request(uri, userAgent, async, DispatcherType.REQUEST);
    }

    /** An async context stub that keeps the listener added to it. */
    private static AsyncContext asyncContext(AtomicReference<AsyncListener> listener) {
        return (AsyncContext) Proxy.newProxyInstance(
                FingerlyAgentSightingsFilterTest.class.getClassLoader(),
                new Class<?>[] {AsyncContext.class},
                (target, method, args) -> {
                    if (method.getName().equals("addListener")) {
                        listener.set((AsyncListener) args[0]);
                    }
                    return null;
                });
    }

    private static HttpServletRequest request(String uri, String userAgent,
            AtomicReference<AsyncListener> async, DispatcherType dispatch) {
        Map<String, String> headers = userAgent == null
                ? Map.of("Host", "example.com")
                : Map.of("Host", "example.com", "User-Agent", userAgent);
        AsyncContext context = async == null ? null : asyncContext(async);
        return (HttpServletRequest) Proxy.newProxyInstance(
                FingerlyAgentSightingsFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (target, method, args) -> {
                    switch (method.getName()) {
                        case "getDispatcherType":
                            return dispatch;
                        case "getMethod":
                            return "GET";
                        case "getRequestURI":
                            return uri;
                        case "getHeaderNames":
                            return Collections.enumeration(headers.keySet());
                        case "getHeaders":
                            String value = headers.get((String) args[0]);
                            return Collections.enumeration(value == null ? List.of() : List.of(value));
                        case "getRemoteAddr":
                            return "20.171.206.9";
                        case "isAsyncStarted":
                            return async != null;
                        case "getAsyncContext":
                            return context;
                        case "getInputStream":
                            throw new AssertionError("the body must never be read");
                        default:
                            return defaultFor(method.getReturnType());
                    }
                });
    }

    private static HttpServletResponse response() {
        AtomicReference<String> contentType = new AtomicReference<>();
        return (HttpServletResponse) Proxy.newProxyInstance(
                FingerlyAgentSightingsFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (target, method, args) -> {
                    switch (method.getName()) {
                        case "setContentType":
                            contentType.set((String) args[0]);
                            return null;
                        case "getContentType":
                            return contentType.get();
                        default:
                            return defaultFor(method.getReturnType());
                    }
                });
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    /** An application that answers with this content type. */
    private static FilterChain serving(String contentType) {
        return (request, response) -> response.setContentType(contentType);
    }

    @Test
    void reportsAnHtmlResponseAndAnswersFromMemory() throws Exception {
        FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(sightings);
        HttpServletRequest request = request("/page", GPTBOT, null);
        assertSame(AgentSighting.UNKNOWN, filter.lookup(request));

        filter.doFilter(request, response(), serving("text/html;charset=UTF-8"));
        sightings.flush();

        assertEquals(1, received.size());
        JsonNode item = received.get(0).path("items").path(0);
        assertEquals(GPTBOT, item.path("user_agent").asText());
        assertEquals("20.171.206.9", item.path("client_ip").asText());
        assertEquals("example.com", item.path("host").asText());
        assertEquals("GET", item.path("method").asText());
        assertFalse(item.has("path"), "the path is never sent");
        AgentSighting answer = filter.lookup(request);
        assertEquals(VisitorKind.AI_BOT, answer.getVisitorKind());
        assertEquals("GPTBot", answer.getAgent());
    }

    @Test
    void reportsOnlyInScopeResponses() throws Exception {
        FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(sightings);
        filter.doFilter(request("/api/items", GPTBOT, null), response(), serving("application/json"));
        filter.doFilter(request("/app.js", GPTBOT, null), response(), serving("text/javascript"));
        filter.doFilter(request("/health", GPTBOT, null), response(), (req, res) -> { });
        sightings.flush();
        assertTrue(received.isEmpty());

        filter.doFilter(request("/robots.txt", GPTBOT, null), response(), serving("text/plain"));
        sightings.flush();
        assertEquals(1, received.size());
    }

    @Test
    void neverDelaysTheResponse() throws Exception {
        // The API holds every report until the test ends, so a response that
        // waited on the network would never finish; no wall-clock limit on
        // a loaded machine decides this test.
        hold = new CountDownLatch(1);
        FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(sightings);
        // 200 waiting make a batch due: the flusher sends it and the API holds it.
        for (int i = 0; i < 300; i++) {
            filter.doFilter(request("/page", GPTBOT + " " + i, null), response(), serving("text/html"));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (received.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, received.size(), "a batch is held at the API");

        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<?> more = thread.submit(() -> {
                for (int i = 300; i < 400; i++) {
                    filter.doFilter(request("/page", GPTBOT + " " + i, null), response(), serving("text/html"));
                }
                return null;
            });
            // Shorter than any wait on an API that never answers can last (a
            // flush's own 5 second deadline, else the 30 seconds the API holds
            // a report), and many times what 100 responses take on a loaded
            // machine.
            more.get(4, TimeUnit.SECONDS);
        } finally {
            thread.shutdownNow();
        }
        assertEquals(1, hold.getCount(), "the batch was still held while those responses finished");
    }

    @Test
    void anAsyncRequestStartedAgainIsReportedWhenItsLastCycleCompletes() throws Exception {
        FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(sightings);
        AtomicReference<AsyncListener> first = new AtomicReference<>();
        HttpServletResponse response = response();
        filter.doFilter(request("/page", GPTBOT, first), response, (req, res) -> { });
        // A second async cycle, as a DeferredResult whose value is a Callable
        // starts: the container hands each listener the new context and
        // forgets every listener that does not add itself to it.
        AtomicReference<AsyncListener> second = new AtomicReference<>();
        first.get().onStartAsync(new AsyncEvent(asyncContext(second)));
        assertSame(first.get(), second.get(), "the listener follows the request into its next cycle");

        response.setContentType("text/html");
        second.get().onComplete(null);
        sightings.flush();
        assertEquals(1, received.size());
    }

    @Test
    void otherDispatchesOfTheSameRequestAreNotReportedAgain() throws Exception {
        FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(sightings);
        AtomicReference<AsyncListener> listener = new AtomicReference<>();
        HttpServletResponse response = response();
        filter.doFilter(request("/page", GPTBOT, listener), response, (req, res) -> { });
        // Mapped to every dispatcher type, the filter also runs for the async
        // dispatch that writes the response, and for error pages, forwards
        // and includes.
        filter.doFilter(request("/page", GPTBOT, null, DispatcherType.ASYNC), response, serving("text/html"));
        for (DispatcherType dispatch : List.of(DispatcherType.ERROR, DispatcherType.FORWARD, DispatcherType.INCLUDE)) {
            filter.doFilter(request("/page", GPTBOT, null, dispatch), response(), serving("text/html"));
        }
        listener.get().onComplete(null);
        sightings.flush();

        assertEquals(1, received.size());
        assertEquals(1, received.get(0).path("items").size());
        assertEquals(1, received.get(0).path("items").path(0).path("count").asInt());
    }

    @Test
    void aFailedChainIsNotReported() {
        FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(sightings);
        assertThrows(ServletException.class, () -> filter.doFilter(
                request("/page", GPTBOT, null), response(), (request, response) -> {
                    response.setContentType("text/html");
                    throw new ServletException("broken");
                }));
        sightings.flush();
        assertTrue(received.isEmpty());
    }

    @Test
    void anAsyncRequestIsReportedWhenItCompletes() throws Exception {
        FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(sightings);
        AtomicReference<AsyncListener> listener = new AtomicReference<>();
        HttpServletResponse response = response();
        filter.doFilter(request("/page", GPTBOT, listener), response, (req, res) -> { });
        assertNotNull(listener.get());
        sightings.flush();
        assertTrue(received.isEmpty(), "nothing is known before the request completes");

        response.setContentType("text/html");
        listener.get().onComplete(null);
        sightings.flush();
        assertEquals(1, received.size());
    }

    @Test
    void aRequestWithoutUserAgentIsReportedEmpty() throws Exception {
        FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(sightings);
        filter.doFilter(request("/", null, null), response(), serving("text/html"));
        sightings.flush();
        assertEquals("", received.get(0).path("items").path(0).path("user_agent").asText());
    }

    @Test
    void aClientIpCallbackThrowingACheckedExceptionNeverReachesTheRequest() throws Exception {
        // As a Kotlin lambda can, through a Java functional interface.
        try (AgentSightings throwing = AgentSightings.builder()
                .client(FingerlyClient.builder()
                        .secretKey("fly_sk_development_test")
                        .allowInsecureLoopback(true)
                        .endpoint("http://127.0.0.1:" + api.getAddress().getPort())
                        .build())
                .clientIp(request -> {
                    throw FingerlyAgentSightingsFilterTest.<RuntimeException>sneaky(new IOException("header"));
                })
                .log(line -> { })
                .build()) {
            FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(throwing);
            HttpServletRequest request = request("/", GPTBOT, null);
            assertSame(AgentSighting.UNKNOWN, filter.lookup(request));
            filter.doFilter(request, response(), serving("text/html"));
            throwing.flush();
            assertTrue(received.isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
