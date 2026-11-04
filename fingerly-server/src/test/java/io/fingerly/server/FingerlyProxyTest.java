package io.fingerly.server;

import static io.fingerly.server.Fixtures.PROXY_KEY;
import static io.fingerly.server.Fixtures.PUBLIC_KEY;
import static io.fingerly.server.Fixtures.SECRET_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FingerlyProxyTest {

    private static final String PATH = "/k7x2qm4a";

    private static FingerlyProxy proxy(FakeApi upstream, Consumer<FingerlyProxy.Builder> tweak) {
        FingerlyProxy.Builder builder = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path(PATH)
                .upstream(upstream.url())
                .clientIp(request -> request.header("CF-Connecting-IP"));
        if (tweak != null) {
            tweak.accept(builder);
        }
        return builder.build();
    }

    private static ProxyRequest request(String method, String path, String body,
            Map<String, String> headers) {
        ProxyRequest.Builder builder = ProxyRequest.builder()
                .method(method)
                .path(path)
                .body(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                .contentLength(body.length());
        headers.forEach(builder::header);
        return builder.build();
    }

    private static Map<String, String> browserHeaders() {
        return Map.of(
                "X-API-Key", PUBLIC_KEY,
                "Origin", "https://shop.test",
                "User-Agent", "Firefox",
                "CF-Connecting-IP", "203.0.113.5",
                "Cookie", "session=secret",
                "Authorization", "Bearer secret",
                "Idempotency-Key", "idem-1");
    }

    private static String headerOf(ProxyResponse response, String name) {
        for (Map.Entry<String, String> header : response.headers()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    @Test
    @SuppressWarnings("deprecation") // The deprecated secretKey path still has to be tested.
    void rejectsInvalidOptions() {
        FingerlyProxy.ClientIp clientIp = request -> "203.0.113.5";
        assertThrows(IllegalArgumentException.class,
                () -> FingerlyProxy.builder().clientIp(clientIp).build(), "no key");
        assertThrows(IllegalArgumentException.class,
                () -> FingerlyProxy.builder().proxyKey(PROXY_KEY).build(), "no clientIp");
        assertThrows(IllegalArgumentException.class, () -> FingerlyProxy.builder()
                .proxyKey(PROXY_KEY).secretKey(SECRET_KEY).clientIp(clientIp).build(), "both keys");
        assertThrows(IllegalArgumentException.class, () -> FingerlyProxy.builder()
                .proxyKey(SECRET_KEY).clientIp(clientIp).build(), "a secret key as proxy key");
        for (String path : new String[] {"k7x2qm4a", "/", "///", " ", "/k7x2qm4a?a=1", "/k7x2qm4a#a"}) {
            assertThrows(IllegalArgumentException.class, () -> FingerlyProxy.builder()
                    .proxyKey(PROXY_KEY).clientIp(clientIp).path(path).build(), path);
        }
        assertThrows(IllegalArgumentException.class, () -> FingerlyProxy.builder()
                .proxyKey(PROXY_KEY).clientIp(clientIp).maxBodyBytes(0).build(), "zero body limit");
        assertThrows(IllegalArgumentException.class, () -> FingerlyProxy.builder()
                .proxyKey(PROXY_KEY).clientIp(clientIp).timeout(Duration.ZERO).build(), "zero timeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"signals\":{}}", "{\"token\":\"t\",\"signals\":{}}"})
    void forwardsEveryCallToTheOneIngestUrl(String body) throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{\"request_id\":\"identified\"}")) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", PATH, body, browserHeaders()));

            assertEquals(200, response.status());
            assertEquals("/api/v1/ingest", upstream.first().path);
            assertEquals(body, upstream.first().bodyText());
        }
    }

    @Test
    void forwardsToTheConfiguredVersionsIngestUrl() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            proxy(upstream, builder -> builder.apiVersion("v2"))
                    .handle(request("POST", PATH, "{}", browserHeaders()));

            assertEquals("/api/v2/ingest", upstream.first().path);
        }
    }

    @Test
    void sendsOnlyVettedVisitorMetadata() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            proxy(upstream, null)
                    .handle(request("POST", PATH, "{\"signals\":{}}", browserHeaders()));

            FakeApi.Recorded forwarded = upstream.first();
            assertEquals(PUBLIC_KEY, forwarded.header("X-Api-Key"));
            assertEquals(PROXY_KEY, forwarded.header("X-Fingerly-Proxy-Key"));
            assertEquals("203.0.113.5", forwarded.header("X-Fingerly-Client-Ip"));
            assertEquals("https://shop.test", forwarded.header("X-Fingerly-Origin"));
            assertEquals("Firefox", forwarded.header("X-Fingerly-User-Agent"));
            assertEquals("idem-1", forwarded.header("Idempotency-Key"));
            assertEquals("java/" + FingerlyClient.VERSION,
                    forwarded.header("X-Fingerly-Proxy-Version"));
            assertNull(forwarded.header("Cookie"));
            assertNull(forwarded.header("Authorization"));
            assertEquals("{\"signals\":{}}", forwarded.bodyText());
        }
    }

    @Test
    void marksResponsesUncacheableAndDropsFraming() throws IOException {
        try (FakeApi upstream = FakeApi.start(exchange -> {
            exchange.getResponseHeaders().add("RateLimit-Remaining", "99");
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=600");
            FakeApi.respond(exchange, 200, "{\"ok\":1}");
        })) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", PATH, "{}", browserHeaders()));

            assertEquals("no-store", headerOf(response, "Cache-Control"));
            assertEquals("99", headerOf(response, "RateLimit-Remaining"));
            assertNull(headerOf(response, "Content-Length"), "framing must not be replayed");
            assertNull(headerOf(response, "Transfer-Encoding"));
        }
    }

    @Test
    void relaysOnlyAllowlistedHeaders() throws IOException {
        try (FakeApi upstream = FakeApi.start(exchange -> {
            exchange.getResponseHeaders().add("X-Request-Id", "req-1");
            exchange.getResponseHeaders().add("Retry-After", "3");
            exchange.getResponseHeaders().add("Fingerly-Balance-Micros", "100");
            exchange.getResponseHeaders().add("Set-Cookie", "a=b");
            exchange.getResponseHeaders().add("X-Powered-By", "api");
            FakeApi.respond(exchange, 200, "{\"ok\":1}");
        })) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", PATH, "{}", browserHeaders()));

            assertEquals("req-1", headerOf(response, "X-Request-Id"));
            assertEquals("3", headerOf(response, "Retry-After"));
            assertNull(headerOf(response, "Set-Cookie"));
            assertNull(headerOf(response, "X-Powered-By"));
            // Nothing relayed may name Fingerly: a blocker can match a header.
            for (Map.Entry<String, String> header : response.headers()) {
                assertFalse(header.getKey().toLowerCase(Locale.ROOT).contains("fingerly"),
                        header.getKey());
                assertFalse(header.getValue().toLowerCase(Locale.ROOT).contains("fingerly"),
                        header.getValue());
            }
        }
    }

    @Test
    void refusesAnythingButPost() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("GET", PATH, "", browserHeaders()));

            assertEquals(405, response.status());
            assertEquals("POST", headerOf(response, "Allow"));
            assertEquals(0, upstream.callCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/k7x2qm4a/admin",
        "/k7x2qm4a/api/v1/identify",
        "/k7x2qm4ab",
        "/other",
        "/",
        "",
        "/api/v1/ingest",
    })
    void refusesEveryOtherPath(String path) throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = proxy(upstream, null);

            assertFalse(proxy.handles(path), "handles() must agree with handle()");
            assertEquals(404, proxy.handle(request("POST", path, "{}", browserHeaders())).status());
            assertEquals(0, upstream.callCount());
        }
    }

    @Test
    void handlesAgreesWithHandleOnItsPath() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = proxy(upstream, null);
            assertTrue(proxy.handles(PATH));
            assertTrue(proxy.handles(PATH + "/"), "trailing slashes are ignored");
            // Path-only: a wrong method on an owned path is this proxy's 405.
            assertEquals(405, proxy.handle(request("GET", PATH, "", browserHeaders())).status());
            assertEquals(200, proxy.handle(request("POST", PATH + "/", "{}", browserHeaders())).status());
        }
    }

    @Test
    void matchesAPathGivenWithATrailingSlash() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = proxy(upstream, builder -> builder.path(PATH + "/"));
            assertEquals(PATH, proxy.path().orElseThrow());
            assertTrue(proxy.handles(PATH));
        }
    }

    @Test
    void answersWhateverIsRoutedToItWithoutAPath() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = proxy(upstream, builder -> builder.path(null));
            assertTrue(proxy.path().isEmpty());
            assertTrue(proxy.handles("/anything"));
            assertEquals(200,
                    proxy.handle(request("POST", "/anything", "{}", browserHeaders())).status());
            assertEquals("/api/v1/ingest", upstream.first().path);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "fly_pk_production_test", "fly_sk_development_test", "invalid",
    })
    void refusesAPublicKeyFromAnotherDeployment(String key) throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", PATH, "{}", Map.of("X-API-Key", key)));

            assertEquals(401, response.status());
            assertEquals(0, upstream.callCount());
        }
    }

    @Test
    void refusesAMissingPublicKey() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", PATH, "{}", Map.of()));

            assertEquals(401, response.status());
            assertEquals(0, upstream.callCount());
        }
    }

    @Test
    void refusesAnOversizedBody() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = proxy(upstream, builder -> builder.maxBodyBytes(8));
            ProxyResponse declared = proxy.handle(
                    request("POST", PATH, "a body well past the limit", browserHeaders()));
            assertEquals(413, declared.status());

            // Undeclared length: the streaming read must still stop at the limit.
            ProxyRequest.Builder undeclared = ProxyRequest.builder()
                    .method("POST")
                    .path(PATH)
                    .body(new ByteArrayInputStream(
                            "a body well past the limit".getBytes(StandardCharsets.UTF_8)))
                    .contentLength(-1);
            browserHeaders().forEach(undeclared::header);
            assertEquals(413, proxy.handle(undeclared.build()).status());

            assertEquals(0, upstream.callCount());
        }
    }

    @Test
    void refusesAClientIpThatCouldInjectAHeader() throws IOException {
        for (String address : new String[] {"1.2.3.4\r\nX-Evil: 1", "1.2.3.4\n", "hé", null}) {
            try (FakeApi upstream = FakeApi.json(200, "{}")) {
                FingerlyProxy proxy = proxy(upstream, builder ->
                        builder.clientIp(request -> address));
                ProxyResponse response =
                        proxy.handle(request("POST", PATH, "{}", browserHeaders()));

                assertEquals(500, response.status(), String.valueOf(address));
                assertEquals(0, upstream.callCount());
            }
        }
    }

    @Test
    void reportsAnUnreachableUpstreamAsBadGateway() {
        FingerlyProxy proxy = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path(PATH)
                .upstream("http://127.0.0.1:1")
                .clientIp(request -> "203.0.113.5")
                .build();

        ProxyResponse response = proxy.handle(request("POST", PATH, "{}", browserHeaders()));
        assertEquals(502, response.status());
        assertEquals("Bad gateway", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("text/plain; charset=utf-8", headerOf(response, "Content-Type"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void refusesAnAnswerLargerThanFourMebibytes(boolean declared) throws IOException {
        byte[] big = new byte[BoundedBody.LIMIT + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        try (FakeApi upstream = FakeApi.start(exchange -> {
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, declared ? big.length : 0);
                exchange.getResponseBody().write(big);
            } catch (IOException hungUp) {
                // The proxy stops reading at the limit.
            }
        })) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", PATH, "{}", browserHeaders()));
            assertEquals(502, response.status());
        }
    }

    @Test
    void decodesACompressedAnswerRatherThanRelayingItsBytes() throws IOException {
        byte[] json = "{\"request_id\":\"\u00fc\"}".getBytes(StandardCharsets.UTF_8);
        try (FakeApi upstream = FakeApi.start(exchange -> {
            try {
                java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream();
                try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(packed)) {
                    gzip.write(json);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, packed.size());
                exchange.getResponseBody().write(packed.toByteArray());
            } catch (IOException cause) {
                throw new IllegalStateException(cause);
            }
        })) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", PATH, "{}", browserHeaders()));
            assertEquals(200, response.status());
            assertEquals(new String(json, StandardCharsets.UTF_8),
                    new String(response.body(), StandardCharsets.UTF_8));
            assertNull(headerOf(response, "Content-Encoding"));
        }
    }

    @Test
    void refusesAContentCodingItCannotDecode() throws IOException {
        try (FakeApi upstream = FakeApi.start(exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "br");
            FakeApi.respond(exchange, 200, "not really brotli");
        })) {
            assertEquals(502, proxy(upstream, null)
                    .handle(request("POST", PATH, "{}", browserHeaders())).status());
        }
    }

    @Test
    void logsOneKeyFreeLinePerFailure() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        FingerlyProxy proxy = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path(PATH)
                .upstream("http://127.0.0.1:1")
                .clientIp(request -> "203.0.113.5")
                .log(lines::add)
                .build();

        proxy.handle(request("POST", PATH, "{}", browserHeaders()));
        proxy.handle(request("POST", PATH, "{}", browserHeaders()));

        assertEquals(2, lines.size(), lines.toString());
        for (String line : lines) {
            assertFalse(line.contains(PROXY_KEY) || line.contains(PUBLIC_KEY), line);
            assertFalse(line.contains("\n") || line.contains("\tat "), "no stack trace: " + line);
        }
    }

    @Test
    void aThrowingLogHookStillAnswersBadGateway() {
        FingerlyProxy proxy = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path(PATH)
                .upstream("http://127.0.0.1:1")
                .clientIp(request -> "203.0.113.5")
                .log(line -> {
                    throw new IllegalStateException("broken logger");
                })
                .build();

        assertEquals(502, proxy.handle(request("POST", PATH, "{}", browserHeaders())).status());
    }

    @Test
    void timesOutWhileReceivingTheBody() throws IOException {
        try (FakeApi upstream = FakeApi.start(exchange -> {
            try {
                // Headers sent, body never finishes.
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                Thread.sleep(2000);
            } catch (IOException | InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        })) {
            FingerlyProxy proxy = proxy(upstream, b -> b.timeout(Duration.ofMillis(200)));

            long started = System.nanoTime();
            ProxyResponse response = proxy.handle(request("POST", PATH, "{}", browserHeaders()));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(504, response.status());
            assertEquals("Gateway timeout", new String(response.body(), StandardCharsets.UTF_8));
            assertEquals("no-store", headerOf(response, "Cache-Control"));
            assertTrue(elapsed.compareTo(Duration.ofMillis(1500)) < 0, "took " + elapsed);
        }
    }

    /**
     * Before JDK 16, cancelling the future sendAsync returned left the
     * exchange running: the connection stayed open, reading, until the
     * upstream finished. The deadline must close it, on every JDK.
     */
    @Test
    void closesTheUpstreamConnectionWhenTheDeadlinePasses() throws Exception {
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        try (FakeApi upstream = FakeApi.start(exchange -> {
            try {
                exchange.sendResponseHeaders(200, 1 << 20);
                // Trickle until the proxy hangs up; a write then fails.
                for (int i = 0; i < 100; i++) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(50);
                }
            } catch (IOException hungUp) {
                closed.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        })) {
            FingerlyProxy proxy = proxy(upstream, b -> b.timeout(Duration.ofMillis(300)));

            assertTrue(proxy.handle(request("POST", PATH, "{}", browserHeaders())).status() >= 500);
            assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "the upstream connection was left open after the deadline");
        }
    }

    @Test
    @SuppressWarnings("deprecation") // The deprecated secretKey path still has to be tested.
    void forwardsTheSecretKeyWithoutAPublicKey() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = FingerlyProxy.builder()
                    .secretKey(SECRET_KEY)
                    .path(PATH)
                    .upstream(upstream.url())
                    .clientIp(request -> "203.0.113.5")
                    .build();

            assertEquals(200, proxy.handle(request("POST", PATH, "{}", Map.of())).status());
            assertEquals(SECRET_KEY, upstream.first().header("X-Api-Key"));
            assertNull(upstream.first().header("X-Fingerly-Proxy-Key"));
            assertEquals("/api/v1/ingest", upstream.first().path);
        }
    }
}
