package io.fingerly.server.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.fingerly.server.AgentSighting;
import io.fingerly.server.AgentSightings;
import io.fingerly.server.FingerlyClient;
import io.fingerly.server.ProxyRequest;
import io.fingerly.server.VisitorKind;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** The agent sightings web filter, driven through Spring's own exchange harness. */
class FingerlyAgentSightingsWebFilterTest {

    private static final String GPTBOT = "Mozilla/5.0 (compatible; GPTBot/1.4; +https://openai.com/gptbot)";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer api;
    private final List<JsonNode> received = Collections.synchronizedList(new ArrayList<>());
    /**
     * Shorter than any wait on an API that never answers can last (a flush's
     * own 5 second deadline, else the 30 seconds the API holds a report), and
     * many times what 100 mock exchanges take on a loaded machine.
     */
    private static final Duration BOUND = Duration.ofSeconds(4);

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

    private static MockServerWebExchange exchange(String path, String userAgent) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header("Host", "example.com")
                .header("User-Agent", userAgent)
                .remoteAddress(new InetSocketAddress("20.171.206.9", 40000)));
    }

    /** An application that answers with this content type and a small body. */
    private static WebFilterChain serving(String contentType) {
        return exchange -> {
            ServerHttpResponse response = exchange.getResponse();
            if (contentType != null) {
                response.getHeaders().set("Content-Type", contentType);
            }
            return response.writeWith(Mono.just(response.bufferFactory()
                    .wrap("<p>hi</p>".getBytes(StandardCharsets.UTF_8))));
        };
    }

    @Test
    void reportsAnHtmlResponseAndAnswersFromMemory() {
        FingerlyAgentSightingsWebFilter filter = new FingerlyAgentSightingsWebFilter(sightings);
        MockServerWebExchange exchange = exchange("/page", GPTBOT);
        assertSame(AgentSighting.UNKNOWN, filter.lookup(exchange));

        filter.filter(exchange, serving("text/html;charset=UTF-8")).block();
        sightings.flush();

        assertEquals(1, received.size());
        JsonNode item = received.get(0).path("items").path(0);
        assertEquals(GPTBOT, item.path("user_agent").asText());
        assertEquals("20.171.206.9", item.path("client_ip").asText());
        assertEquals("example.com", item.path("host").asText());
        assertEquals("GET", item.path("method").asText());
        assertEquals(VisitorKind.AI_BOT, filter.lookup(exchange("/other", GPTBOT)).getVisitorKind());
    }

    @Test
    void reportsOnlyInScopeResponses() {
        FingerlyAgentSightingsWebFilter filter = new FingerlyAgentSightingsWebFilter(sightings);
        filter.filter(exchange("/api/items", GPTBOT), serving("application/json")).block();
        filter.filter(exchange("/app.js", GPTBOT), serving("text/javascript")).block();
        filter.filter(exchange("/health", GPTBOT), serving(null)).block();
        sightings.flush();
        assertTrue(received.isEmpty());

        filter.filter(exchange("/llms.txt", GPTBOT), serving("text/plain")).block();
        sightings.flush();
        assertEquals(1, received.size());
    }

    @Test
    void neverDelaysTheResponse() throws InterruptedException {
        // The API holds every report until the test ends, so a response that
        // waited on the network would never finish; no wall-clock limit on
        // a loaded machine decides this test.
        hold = new CountDownLatch(1);
        FingerlyAgentSightingsWebFilter filter = new FingerlyAgentSightingsWebFilter(sightings);
        // 200 waiting make a batch due: the flusher sends it and the API holds it.
        for (int i = 0; i < 300; i++) {
            filter.filter(exchange("/page", GPTBOT + " " + i), serving("text/html")).block(BOUND);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (received.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, received.size(), "a batch is held at the API");

        // While it is held, 100 more responses finish in less time than any
        // one wait on the API could last.
        long started = System.nanoTime();
        for (int i = 300; i < 400; i++) {
            filter.filter(exchange("/page", GPTBOT + " " + i), serving("text/html")).block(BOUND);
        }
        long took = System.nanoTime() - started;
        assertTrue(took < BOUND.toNanos(), "100 responses took " + TimeUnit.NANOSECONDS.toMillis(took) + " ms");
        assertEquals(1, hold.getCount(), "the batch was still held while those responses finished");
    }

    @Test
    void aClientIpCallbackThrowingACheckedExceptionNeverReachesTheExchange() {
        // As a Kotlin lambda can, through a Java functional interface.
        try (AgentSightings throwing = AgentSightings.builder()
                .client(FingerlyClient.builder()
                        .secretKey("fly_sk_development_test")
                        .allowInsecureLoopback(true)
                        .endpoint("http://127.0.0.1:" + api.getAddress().getPort())
                        .build())
                .clientIp(request -> {
                    throw FingerlyAgentSightingsWebFilterTest.<RuntimeException>sneaky(new IOException("header"));
                })
                .log(line -> { })
                .build()) {
            FingerlyAgentSightingsWebFilter filter = new FingerlyAgentSightingsWebFilter(throwing);
            MockServerWebExchange exchange = exchange("/page", GPTBOT);
            assertSame(AgentSighting.UNKNOWN, filter.lookup(exchange));
            filter.filter(exchange, serving("text/html")).block();
            throwing.flush();
            assertTrue(received.isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
