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
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FingerlyProxyTest {

    private static final String IDENTIFY = "/metrics/api/v1/identify";

    private static FingerlyProxy proxy(FakeApi upstream, Consumer<FingerlyProxy.Builder> tweak) {
        FingerlyProxy.Builder builder = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .prefix("/metrics")
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
        assertThrows(IllegalArgumentException.class, () -> FingerlyProxy.builder()
                .proxyKey(PROXY_KEY).clientIp(clientIp).prefix("metrics").build(), "relative prefix");
        assertThrows(IllegalArgumentException.class, () -> FingerlyProxy.builder()
                .proxyKey(PROXY_KEY).clientIp(clientIp).maxBodyBytes(0).build(), "zero body limit");
        assertThrows(IllegalArgumentException.class, () -> FingerlyProxy.builder()
                .proxyKey(PROXY_KEY).clientIp(clientIp).timeout(Duration.ZERO).build(), "zero timeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {IDENTIFY, "/metrics/api/v1/events/request-1/supplement"})
    void forwardsBothProxyableRoutes(String path) throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{\"request_id\":\"identified\"}")) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", path, "{}", browserHeaders()));

            assertEquals(200, response.status());
            assertEquals(path.replace("/metrics", ""), upstream.first().path);
        }
    }

    @Test
    void sendsOnlyVettedVisitorMetadata() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            proxy(upstream, null)
                    .handle(request("POST", IDENTIFY, "{\"signals\":{}}", browserHeaders()));

            FakeApi.Recorded forwarded = upstream.first();
            assertEquals(PUBLIC_KEY, forwarded.header("X-Api-Key"));
            assertEquals(PROXY_KEY, forwarded.header("X-Fingerly-Proxy-Key"));
            assertEquals("203.0.113.5", forwarded.header("X-Fingerly-Client-Ip"));
            assertEquals("https://shop.test", forwarded.header("X-Fingerly-Origin"));
            assertEquals("Firefox", forwarded.header("X-Fingerly-User-Agent"));
            assertEquals("idem-1", forwarded.header("Idempotency-Key"));
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
                    .handle(request("POST", IDENTIFY, "{}", browserHeaders()));

            assertEquals("no-store", headerOf(response, "Cache-Control"));
            assertEquals("99", headerOf(response, "RateLimit-Remaining"));
            assertNull(headerOf(response, "Content-Length"), "framing must not be replayed");
            assertNull(headerOf(response, "Transfer-Encoding"));
        }
    }

    @Test
    void refusesAnythingButPost() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("GET", IDENTIFY, "", browserHeaders()));

            assertEquals(405, response.status());
            assertEquals("POST", headerOf(response, "Allow"));
            assertEquals(0, upstream.callCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/metrics/api/v1/events",
        "/metrics/api/v1/events/request-1",
        "/metrics/api/v1/events/a/b/supplement",
        "/metrics/api/v1/events//supplement",
        "/metrics/api/v1/identify/extra",
        "/metrics/api/v2/identify",
        "/metrics/admin",
        "/elsewhere/api/v1/identify",
    })
    void refusesEveryRouteThatIsNotProxyable(String path) throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = proxy(upstream, null);

            assertFalse(proxy.handles(path), "handles() must agree with handle()");
            assertEquals(404, proxy.handle(request("POST", path, "{}", browserHeaders())).status());
            assertEquals(0, upstream.callCount());
        }
    }

    @Test
    void handlesAgreesWithHandleOnProxyableRoutes() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = proxy(upstream, null);
            assertTrue(proxy.handles(IDENTIFY));
            assertTrue(proxy.handles("/metrics/api/v1/events/request-1/supplement"));
            // Path-only: a wrong method on an owned path is this proxy's 405.
            assertTrue(proxy.handles(IDENTIFY));
            assertEquals(405, proxy.handle(request("GET", IDENTIFY, "", browserHeaders())).status());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "fly_pk_production_test", "fly_sk_development_test", "invalid",
    })
    void refusesAPublicKeyFromAnotherDeployment(String key) throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", IDENTIFY, "{}", Map.of("X-API-Key", key)));

            assertEquals(401, response.status());
            assertEquals(0, upstream.callCount());
        }
    }

    @Test
    void refusesAMissingPublicKey() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            ProxyResponse response = proxy(upstream, null)
                    .handle(request("POST", IDENTIFY, "{}", Map.of()));

            assertEquals(401, response.status());
            assertEquals(0, upstream.callCount());
        }
    }

    @Test
    void refusesAnOversizedBody() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = proxy(upstream, builder -> builder.maxBodyBytes(8));
            ProxyResponse declared = proxy.handle(
                    request("POST", IDENTIFY, "a body well past the limit", browserHeaders()));
            assertEquals(413, declared.status());

            // Undeclared length: the streaming read must still stop at the limit.
            ProxyRequest.Builder undeclared = ProxyRequest.builder()
                    .method("POST")
                    .path(IDENTIFY)
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
                        proxy.handle(request("POST", IDENTIFY, "{}", browserHeaders()));

                assertEquals(500, response.status(), String.valueOf(address));
                assertEquals(0, upstream.callCount());
            }
        }
    }

    @Test
    void reportsAnUnreachableUpstreamAsBadGateway() {
        FingerlyProxy proxy = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .prefix("/metrics")
                .upstream("http://127.0.0.1:1")
                .clientIp(request -> "203.0.113.5")
                .build();

        assertEquals(502, proxy.handle(request("POST", IDENTIFY, "{}", browserHeaders())).status());
    }

    @Test
    void acceptsAPrefixThatTheContainerAlreadyStripped() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = proxy(upstream, null);
            assertTrue(proxy.handles("/api/v1/identify"));
            assertEquals(200,
                    proxy.handle(request("POST", "/api/v1/identify", "{}", browserHeaders())).status());
        }
    }

    @Test
    @SuppressWarnings("deprecation") // The deprecated secretKey path still has to be tested.
    void forwardsTheSecretKeyWithoutAPublicKey() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = FingerlyProxy.builder()
                    .secretKey(SECRET_KEY)
                    .prefix("/metrics")
                    .upstream(upstream.url())
                    .clientIp(request -> "203.0.113.5")
                    .build();

            assertEquals(200, proxy.handle(request("POST", IDENTIFY, "{}", Map.of())).status());
            assertEquals(SECRET_KEY, upstream.first().header("X-Api-Key"));
            assertNull(upstream.first().header("X-Fingerly-Proxy-Key"));
        }
    }
}
