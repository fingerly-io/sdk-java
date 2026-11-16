package io.fingerly.server;

import static io.fingerly.server.Fixtures.PROXY_KEY;
import static io.fingerly.server.Fixtures.PUBLIC_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Channel choice, upstream validation, redirect, status range, exact method
 * and failure log rules of the proxy. Mock upstreams listen on 127.0.0.1 only.
 */
class ProxyHardeningTest {

    private static final String PATH = "/k7x2qm4a";
    private static final String BODY = "{\"v\":3}";

    private static FingerlyProxy.Builder builder(String upstream) {
        return FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path(PATH)
                .allowInsecureLoopback(true)
                .upstream(upstream)
                .clientIp(request -> "203.0.113.5");
    }

    private static ProxyRequest request(String method, String path, Map<String, String> headers) {
        ProxyRequest.Builder b = ProxyRequest.builder()
                .method(method)
                .path(path)
                .body(new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8)))
                .contentLength(BODY.length());
        headers.forEach(b::header);
        return b.build();
    }

    private static Map<String, String> browser() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-API-Key", PUBLIC_KEY);
        h.put("User-Agent", "Firefox");
        h.put("Origin", "https://shop.test");
        return h;
    }

    private static String headerOf(ProxyResponse response, String name) {
        for (Map.Entry<String, String> header : response.headers()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    // BE-16: one channel only, and the page origin wins.

    @Test
    void bothChannelHeadersForwardTheOriginOnly() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            Map<String, String> h = browser();
            h.put("X-Fingerly-SDK-Platform", "ios");
            ProxyResponse response = builder(upstream.url()).build().handle(request("POST", PATH, h));
            assertEquals(200, response.status());
            assertEquals("https://shop.test", upstream.first().header("X-Fingerly-Origin"));
            assertNull(upstream.first().header("X-Fingerly-SDK-Platform"));
        }
    }

    @Test
    void aNativeCallWithoutOriginForwardsThePlatformOnly() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("X-API-Key", PUBLIC_KEY);
            h.put("X-Fingerly-SDK-Platform", "android");
            assertEquals(200, builder(upstream.url()).build().handle(request("POST", PATH, h)).status());
            assertEquals("android", upstream.first().header("X-Fingerly-SDK-Platform"));
            assertNull(upstream.first().header("X-Fingerly-Origin"));
        }
    }

    // BE-16: a plain HTTP upstream only for loopback, only with the flag.

    @ParameterizedTest
    @ValueSource(strings = {
        "http://upstream.invalid",
        "http://192.0.2.10:8080",
        "http://127.0.0.2",
        "http://localhost.invalid",
        "ftp://upstream.invalid",
        "upstream.invalid",
        "",
    })
    void refusesAnUpstreamThatIsNotHttps(String value) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> builder(value).build(), value);
        assertFalse(refused.getMessage().contains("upstream.invalid"), refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:9", "http://localhost:9", "http://[::1]:9"})
    void refusesAPlainHttpLoopbackUpstreamWithoutTheDevelopmentFlag(String value) {
        assertThrows(IllegalArgumentException.class, () -> FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .clientIp(request -> "203.0.113.5")
                .upstream(value)
                .build(), value);
        // With the flag, loopback only.
        builder(value).build();
    }

    // Visitor headers the API trusts are never passed through.

    @Test
    void aVisitorCannotSetAHeaderTheApiTrusts() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            Map<String, String> h = browser();
            h.put("X-Fingerly-Client-IP", "198.51.100.66");
            h.put("X-Forwarded-For", "198.51.100.66");
            h.put("X-Fingerly-Origin", "https://evil.test");
            h.put("X-Fingerly-Proxy-Key", "fly_px_development_attacker");
            h.put("X-Fingerly-User-Agent", "Spoofed");
            h.put("X-Fingerly-Edge-TLS-Version", "TLSv1.3");
            h.put("X-Fingerly-Proxy-Version", "spoof/9");
            h.put("Idempotency-Key", "a\r\nX-Fingerly-Client-IP: 198.51.100.66");
            assertEquals(200, builder(upstream.url()).build().handle(request("POST", PATH, h)).status());
            FakeApi.Recorded seen = upstream.first();
            assertEquals(List.of("203.0.113.5"), seen.headers.get("X-Fingerly-Client-IP"));
            assertEquals(List.of("https://shop.test"), seen.headers.get("X-Fingerly-Origin"));
            assertEquals(List.of(PROXY_KEY), seen.headers.get("X-Fingerly-Proxy-Key"));
            assertEquals(List.of("Firefox"), seen.headers.get("X-Fingerly-User-Agent"));
            assertNull(seen.header("X-Forwarded-For"));
            assertNull(seen.header("X-Fingerly-Edge-TLS-Version"));
            assertNull(seen.header("Idempotency-Key"));
            assertFalse(seen.header("X-Fingerly-Proxy-Version").startsWith("spoof"));
        }
    }
}
