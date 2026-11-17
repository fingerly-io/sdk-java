package io.fingerly.server;

import static io.fingerly.server.Fixtures.PROXY_KEY;
import static io.fingerly.server.Fixtures.PUBLIC_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    // BE-16 and PRX-05: the upstream option is a bare https origin.

    @ParameterizedTest
    @ValueSource(strings = {
        "http://upstream.invalid",
        "http://192.0.2.10:8080",
        "http://127.0.0.2",
        "http://localhost.invalid",
        "https://upstream.invalid/prefix",
        "https://upstream.invalid/a/../b",
        "https://upstream.invalid//",
        "https://upstream.invalid?",
        "https://upstream.invalid?a=1",
        "https://upstream.invalid#",
        "https://upstream.invalid#f",
        "https://user:pass@upstream.invalid",
        "https://upstream.invalid\n",
        "https://upstream.invalid ",
        " https://upstream.invalid",
        "https://upstream.invalid\r\nx-injected: 1",
        "https://upstream.invalid\t",
        "ftp://upstream.invalid",
        "upstream.invalid",
        "https://",
        "https://upstream.invalid:0",
        "",
    })
    void refusesAnUpstreamThatIsNotABareHttpsOrigin(String value) {
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

    @Test
    void acceptsAnHttpsOriginAndStoresOnlyItsParsedParts() {
        assertTrue(builder("https://API.example.invalid:8443/").build().toString()
                .contains("upstream=https://api.example.invalid:8443/api/v1/ingest"));
        assertTrue(builder("https://api.example.invalid").build().toString()
                .contains("upstream=https://api.example.invalid/api/v1/ingest"));
    }

    // PRX-01: redirects are never followed, whoever supplies the client.

    @Test
    void refusesASuppliedClientThatFollowsRedirects() throws IOException {
        for (HttpClient.Redirect policy : new HttpClient.Redirect[] {
            HttpClient.Redirect.ALWAYS, HttpClient.Redirect.NORMAL}) {
            HttpClient following = HttpClient.newBuilder().followRedirects(policy).build();
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> builder("https://api.example.invalid").httpClient(following).build());
            assertFalse(refused.getMessage().contains(PROXY_KEY));
        }
        builder("https://api.example.invalid")
                .httpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build())
                .build();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void aRedirectFromUpstreamIsABadGatewayAndIsNotFollowed(int status) throws IOException {
        List<String> leaked = new ArrayList<>();
        try (FakeApi target = FakeApi.start(exchange -> {
                    leaked.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Fingerly-Proxy-Key")));
                    FakeApi.respond(exchange, 200, "{}");
                });
                FakeApi upstream = FakeApi.start(exchange -> {
                    try {
                        exchange.getResponseHeaders().add("Location", target.url() + "/elsewhere");
                        exchange.sendResponseHeaders(status, -1);
                    } catch (IOException cause) {
                        throw new IllegalStateException(cause);
                    }
                })) {
            HttpClient never = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            for (FingerlyProxy proxy : new FingerlyProxy[] {
                builder(upstream.url()).build(), builder(upstream.url()).httpClient(never).build()}) {
                ProxyResponse response = proxy.handle(request("POST", PATH, browser()));
                assertEquals(502, response.status());
                assertEquals("no-store", headerOf(response, "Cache-Control"));
                assertNull(headerOf(response, "Location"));
            }
            assertEquals(List.of(), leaked, "the redirect target was called");
        }
    }

    // PRX-03: a status outside 200 to 599 is a bad gateway.

    @ParameterizedTest
    @ValueSource(strings = {"099", "000", "600", "700", "999"})
    void anUpstreamStatusOutOfRangeIsABadGateway(String status) throws Exception {
        try (RawUpstream upstream = new RawUpstream(
                "HTTP/1.1 " + status + " Odd\r\nContent-Type: application/json\r\n"
                        + "Content-Length: 2\r\nConnection: close\r\n\r\n{}")) {
            ProxyResponse response = builder(upstream.url()).build()
                    .handle(request("POST", PATH, browser()));
            assertEquals(502, response.status(), "upstream status " + status);
            assertEquals("no-store", headerOf(response, "Cache-Control"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 409, 503})
    void anUpstreamStatusInRangeIsRelayed(int status) throws IOException {
        try (FakeApi upstream = FakeApi.json(status, "{}")) {
            assertEquals(status,
                    builder(upstream.url()).build().handle(request("POST", PATH, browser())).status());
        }
    }

    // PRX-04: exact method, exact path, and no-store on every refusal.

    @ParameterizedTest
    @ValueSource(strings = {"post", "Post", "pOST", "GET", "PUT", "DELETE", "options", "PATCH"})
    void onlyAnExactPostIsForwarded(String method) throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            ProxyResponse response = builder(upstream.url())
                    .allowedOrigins("https://shop.test")
                    .build()
                    .handle(request(method, PATH, browser()));
            assertEquals(405, response.status(), method);
            assertEquals("no-store", headerOf(response, "Cache-Control"));
            assertEquals("POST, OPTIONS", headerOf(response, "Allow"));
            assertEquals(0, upstream.callCount(), method);
        }
    }

    @Test
    void optionsIsAPreflightOnlyWithCorsConfigured() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            ProxyResponse plain = builder(upstream.url()).build().handle(request("OPTIONS", PATH, browser()));
            assertEquals(405, plain.status());
            assertEquals("POST", headerOf(plain, "Allow"));
            assertEquals("no-store", headerOf(plain, "Cache-Control"));
            ProxyResponse preflight = builder(upstream.url()).allowedOrigins("https://shop.test").build()
                    .handle(request("OPTIONS", PATH, browser()));
            assertEquals(204, preflight.status());
            assertEquals("no-store", headerOf(preflight, "Cache-Control"));
        }
    }

    @Test
    void nearMissPathsAreUncacheable404sAndNeverReachUpstream() throws IOException {
        try (FakeApi upstream = FakeApi.json(200, "{}")) {
            FingerlyProxy proxy = builder(upstream.url()).build();
            for (String path : new String[] {PATH + "/", PATH + "/..", PATH + "/%2e%2e/api/v1/events",
                "/K7X2QM4A", PATH + ";x=1", "/" + PATH, PATH + "?a=1", PATH + "%2f", "/%6b7x2qm4a",
                "/x/../k7x2qm4a", "/./k7x2qm4a"}) {
                ProxyResponse response = proxy.handle(request("POST", path, browser()));
                assertEquals(404, response.status(), path);
                assertEquals("no-store", headerOf(response, "Cache-Control"), path);
            }
            assertEquals(0, upstream.callCount());
        }
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

    // PRX-05: a failure line names the kind of failure, not the upstream.

    @Test
    void aFailureLineNamesNeitherTheUpstreamNorTheKey() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = probe.getLocalPort();
        }
        List<String> lines = new ArrayList<>();
        FingerlyProxy proxy = builder("http://127.0.0.1:" + closedPort).log(lines::add).build();
        ProxyResponse response = proxy.handle(request("POST", PATH, browser()));
        assertEquals(502, response.status());
        assertEquals(1, lines.size(), lines.toString());
        for (String text : List.of(lines.get(0), new String(response.body(), StandardCharsets.UTF_8))) {
            assertFalse(text.contains("127.0.0.1"), text);
            assertFalse(text.contains(String.valueOf(closedPort)), text);
            assertFalse(text.contains("ingest"), text);
            assertFalse(text.contains(PROXY_KEY) || text.contains(PUBLIC_KEY), text);
        }
    }

    /** A one-shot raw socket upstream on 127.0.0.1 that answers fixed bytes. */
    private static final class RawUpstream implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread worker;
        private final AtomicInteger served = new AtomicInteger();

        RawUpstream(String answer) throws IOException {
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        drainHead(client.getInputStream());
                        OutputStream out = client.getOutputStream();
                        out.write(answer.getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        served.incrementAndGet();
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        /** Reads the request headers and a Content-Length body, enough to answer. */
        private static void drainHead(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                head.append((char) b);
                if (head.toString().endsWith("\r\n\r\n")) {
                    break;
                }
            }
            for (String line : head.toString().split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    int length = Integer.parseInt(line.substring(15).trim());
                    in.readNBytes(length);
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
