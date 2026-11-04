package io.fingerly.server;

import static io.fingerly.server.Fixtures.PROXY_KEY;
import static io.fingerly.server.Fixtures.PUBLIC_KEY;
import static io.fingerly.server.Fixtures.REQUEST_ID;
import static io.fingerly.server.Fixtures.SECRET_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedHttpTest {

    @Test
    void sharesOneClientUntilItsLifetimeEnds() {
        long start = System.nanoTime();
        HttpClient first = SharedHttp.client(start);
        assertSame(first, SharedHttp.client(start + 1));
        HttpClient next = SharedHttp.client(start + SharedHttp.LIFETIME.toNanos() + 1);
        assertNotSame(first, next, "a client older than its lifetime must be replaced");
        assertSame(next, SharedHttp.client(start + SharedHttp.LIFETIME.toNanos() + 2));
    }

    @Test
    void speaksHttp11AndNeverFollowsRedirects() {
        HttpClient client = SharedHttp.newClient();
        assertEquals(HttpClient.Version.HTTP_1_1, client.version());
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        assertTrue(client.cookieHandler().isEmpty(), "no cookie jar");
    }

    /** A proxy or client built per request must still reuse connections. */
    @Test
    void reusesConnectionsAcrossInstances() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.event())) {
            for (int i = 0; i < 20; i++) {
                FingerlyClient.builder().secretKey(SECRET_KEY).endpoint(api.url()).build()
                        .events().get(REQUEST_ID);
                FingerlyProxy.builder()
                        .proxyKey(PROXY_KEY)
                        .upstream(api.url())
                        .clientIp(request -> "203.0.113.5")
                        .build()
                        .handle(ProxyRequest.builder()
                                .method("POST")
                                .path("/p")
                                .header("X-API-Key", PUBLIC_KEY)
                                .body("{}".getBytes(StandardCharsets.UTF_8))
                                .build());
            }
            Set<Integer> ports = new HashSet<>();
            for (FakeApi.Recorded request : api.requests) {
                ports.add(request.remotePort);
                assertNull(request.header("Upgrade"), "no h2c upgrade offer");
            }
            assertEquals(40, api.callCount());
            assertTrue(ports.size() <= 2, "40 sequential calls used " + ports.size() + " sockets");
        }
    }
}
