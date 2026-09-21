package io.fingerly.server;

import static io.fingerly.server.Fixtures.REQUEST_ID;
import static io.fingerly.server.Fixtures.SECRET_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FingerlyClientTest {

    private static FingerlyClient client(FakeApi api) {
        return FingerlyClient.builder().secretKey(SECRET_KEY).endpoint(api.url()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-a-fingerly-key",
        "fly_pk_production_test",
        "fly_px_development_test",
        "fly_mk_test",
        "fly_sk_unknown_test",
        "fly_sk_production_",
        "fly_sk_production_test\n",
    })
    void rejectsUnsupportedKeys(String secretKey) {
        assertThrows(IllegalArgumentException.class, () -> FingerlyClient.builder()
                .secretKey(secretKey)
                .endpoint("https://custom.test")
                .build());
    }

    @Test
    void rejectsInvalidEndpoints() {
        for (String endpoint : new String[] {
            "file:///private", "https://user:pass@host", "https://host?key=value",
            "https://host#fragment", "not-a-url",
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> FingerlyClient.builder().secretKey(SECRET_KEY).endpoint(endpoint).build(),
                    endpoint);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "v0", "v1/../v2", "v2?key=value", "v2\n"})
    void rejectsInvalidApiVersions(String apiVersion) {
        assertThrows(IllegalArgumentException.class, () -> FingerlyClient.builder()
                .secretKey(SECRET_KEY)
                .apiVersion(apiVersion)
                .build());
    }

    @Test
    void rejectsInvalidTimeouts() {
        for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), null}) {
            assertThrows(IllegalArgumentException.class,
                    () -> FingerlyClient.builder().secretKey(SECRET_KEY).timeout(timeout).build());
        }
    }

    @Test
    void sendsTheKeyAndIdentifiesTheSdk() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.event())) {
            client(api).events().get(REQUEST_ID);

            FakeApi.Recorded request = api.first();
            assertEquals(SECRET_KEY, request.header("X-Api-Key"));
            assertEquals("application/json", request.header("Accept"));
            assertTrue(request.header("User-Agent").startsWith("fingerly-java/"));
            assertEquals("/api/v1/events/" + REQUEST_ID, request.path);
        }
    }

    @Test
    void escapesTheRequestIdIntoOneSegment() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.event())) {
            client(api).events().get("id/a");
            assertEquals("/api/v1/events/id%2Fa", api.first().path);
        }
    }

    @Test
    void appliesTheApiVersionToEveryRequest() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.event())) {
            FingerlyClient fingerly = FingerlyClient.builder()
                    .secretKey(SECRET_KEY)
                    .endpoint(api.url())
                    .apiVersion("v2")
                    .build();
            fingerly.events().get(REQUEST_ID);
            assertEquals("/api/v2/events/" + REQUEST_ID, api.first().path);
        }
    }

    @Test
    void neverFollowsRedirects() throws IOException {
        try (FakeApi elsewhere = FakeApi.json(200, Fixtures.event());
                FakeApi api = FakeApi.start(exchange -> {
                    exchange.getResponseHeaders().add("Location", elsewhere.url() + "/api/v1/events/x");
                    try {
                        exchange.sendResponseHeaders(302, -1);
                    } catch (IOException cause) {
                        throw new IllegalStateException(cause);
                    }
                })) {

            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).events().get(REQUEST_ID));
            assertEquals(302, failure.getStatus());
            assertEquals(0, elsewhere.callCount(), "the secret key was replayed to another host");
        }
    }

    @Test
    void reportsStructuredErrorMetadata() throws IOException {
        try (FakeApi api = FakeApi.json(404, Fixtures.error("event_not_found", 404))) {
            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).events().get("missing"));

            assertEquals(404, failure.getStatus());
            assertEquals("event_not_found", failure.getCode());
            assertEquals("trace", failure.getRequestId());
            assertTrue(failure.isEventNotFound());
        }
    }

    @Test
    void eventNotFoundIsNarrow() throws IOException {
        try (FakeApi api = FakeApi.json(404, Fixtures.error("other", 404))) {
            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).events().get("missing"));
            assertFalse(failure.isEventNotFound());
        }
        try (FakeApi api = FakeApi.json(410, Fixtures.error("event_not_found", 410))) {
            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).events().get("missing"));
            assertFalse(failure.isEventNotFound());
        }
    }

    @Test
    void neverExposesTheBodyOrTheKey() throws IOException {
        try (FakeApi api = FakeApi.start(exchange -> FakeApi.respond(exchange, 502, SECRET_KEY))) {
            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).events().get("missing"));

            assertEquals("Fingerly API returned HTTP 502", failure.getMessage());
            assertFalse(failure.getMessage().contains(SECRET_KEY));
            assertNull(failure.getCode());
        }
    }

    @Test
    void redactsTheKeyFromErrorMetadata() throws IOException {
        String body = "{\"error\":{\"code\":\"unauthorized\",\"details\":{\"presented\":\""
                + SECRET_KEY + "\"}},\"request_id\":\"trace-" + SECRET_KEY + "\"}";
        try (FakeApi api = FakeApi.json(401, body)) {
            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).events().get("missing"));

            assertEquals("trace-[redacted]", failure.getRequestId());
            assertNotNull(failure.getDetails());
            assertEquals("[redacted]", failure.getDetails().path("presented").asText());
        }
    }

    @Test
    void timesOutWhileReceivingTheBody() throws IOException {
        try (FakeApi api = FakeApi.start(exchange -> {
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
            FingerlyClient fingerly = FingerlyClient.builder()
                    .secretKey(SECRET_KEY)
                    .endpoint(api.url())
                    .timeout(Duration.ofMillis(200))
                    .build();
            assertThrows(FingerlyTimeoutException.class, () -> fingerly.events().get(REQUEST_ID));
        }
    }

    @Test
    void reportsAnUnreachableApi() {
        // Port 1 on loopback refuses immediately.
        FingerlyClient fingerly = FingerlyClient.builder()
                .secretKey(SECRET_KEY)
                .endpoint("http://127.0.0.1:1")
                .build();
        assertThrows(FingerlyConnectionException.class, () -> fingerly.events().get(REQUEST_ID));
    }

    @Test
    void neverRetries() throws IOException {
        try (FakeApi api = FakeApi.json(404, Fixtures.error("event_not_found", 404))) {
            FingerlyClient fingerly = client(api);
            assertThrows(FingerlyApiException.class, () -> fingerly.events().get("id"));
            assertThrows(FingerlyApiException.class, () -> fingerly.events().list());
            assertEquals(2, api.callCount());
        }
    }

    @Test
    void reusesOneEventsService() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.event())) {
            FingerlyClient fingerly = client(api);
            assertSame(fingerly.events(), fingerly.events());
        }
    }

    @Test
    void decodesOccurredAtAsAnInstant() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.event())) {
            Event event = client(api).events().get(REQUEST_ID);
            assertEquals(Instant.parse("2026-09-17T12:00:00Z"), event.getOccurredAt());
        }
    }
}
