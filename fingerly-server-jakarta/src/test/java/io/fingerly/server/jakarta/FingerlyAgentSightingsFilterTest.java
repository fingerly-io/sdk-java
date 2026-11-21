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
import jakarta.servlet.AsyncListener;
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
    private volatile long delayMillis;
    private AgentSightings sightings;

    @BeforeEach
    void start() throws IOException {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            received.add(body);
            try {
                Thread.sleep(delayMillis);
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
        sightings.close();
        api.stop(0);
    }

    /** A request stub; a non-null {@code async} makes it asynchronous. */
    private static HttpServletRequest request(String uri, String userAgent,
            AtomicReference<AsyncListener> async) {
        Map<String, String> headers = userAgent == null
                ? Map.of("Host", "example.com")
                : Map.of("Host", "example.com", "User-Agent", userAgent);
        AsyncContext context = (AsyncContext) Proxy.newProxyInstance(
                FingerlyAgentSightingsFilterTest.class.getClassLoader(),
                new Class<?>[] {AsyncContext.class},
                (target, method, args) -> {
                    if (method.getName().equals("addListener")) {
                        async.set((AsyncListener) args[0]);
                    }
                    return null;
                });
        return (HttpServletRequest) Proxy.newProxyInstance(
                FingerlyAgentSightingsFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (target, method, args) -> {
                    switch (method.getName()) {
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
        delayMillis = 1_500;
        FingerlyAgentSightingsFilter filter = new FingerlyAgentSightingsFilter(sightings);
        long started = System.nanoTime();
        for (int i = 0; i < 300; i++) {
            filter.doFilter(request("/page", GPTBOT + " " + i, null), response(), serving("text/html"));
        }
        long took = System.nanoTime() - started;
        assertTrue(took < TimeUnit.MILLISECONDS.toNanos(1_000),
                "300 reports took " + TimeUnit.NANOSECONDS.toMillis(took) + " ms");
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
}
