package io.fingerly.server;

import static io.fingerly.server.Fixtures.REQUEST_ID;
import static io.fingerly.server.Fixtures.SECRET_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventsVerifyTest {

    private static final String EVENT = "/api/v1/events/" + REQUEST_ID;
    private static final String CONSUME = EVENT + "/consume";
    private static final String CONSUMED =
            "{\"request_id\":\"" + REQUEST_ID + "\",\"consumed_at\":\"2026-11-17T10:00:00Z\",\"first\":true}";
    private static final String ALREADY_LITERAL =
            "{\"code\":\"event_already_consumed\",\"consumed_at\":\"2026-11-17T10:00:00Z\"}";
    private static final String ALREADY_ENVELOPE =
            "{\"error\":{\"code\":\"event_already_consumed\",\"message\":\"m\",\"status\":409,"
                    + "\"details\":[{\"field\":\"consumed_at\",\"issue\":\"2026-11-17T10:00:00Z\","
                    + "\"location\":\"body\"}]}}";

    private static FingerlyClient client(String url) {
        return FingerlyClient.builder()
                .secretKey(SECRET_KEY)
                .allowInsecureLoopback(true)
                .endpoint(url)
                .timeout(Duration.ofSeconds(2))
                .build();
    }

    private static String error(int status, String code) {
        return "{\"error\":{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"m\"}}";
    }

    /** An event whose fields are given as JSON members, aged {@code ageSeconds}. */
    private static String event(long ageSeconds, String members) {
        return event("\"" + Instant.now().minusSeconds(ageSeconds) + "\"", members);
    }

    private static String event(String occurredAt, String members) {
        return "{\"request_id\":\"" + REQUEST_ID + "\""
                + (occurredAt == null ? "" : ",\"occurred_at\":" + occurredAt)
                + (members.isEmpty() ? "" : "," + members) + "}";
    }

    private static final String GOOD =
            "\"visitor_id\":\"v1\",\"tag\":\"checkout\",\"suspect_score\":12,\"suspect_level\":\"low\"";

    /** A server answering the read with {@code read} and the consume with {@code consume}. */
    private static FakeApi api(int readStatus, String read, int consumeStatus, String consume)
            throws IOException {
        return FakeApi.start(exchange -> answer(exchange, readStatus, read, consumeStatus, consume));
    }

    private static void answer(HttpExchange exchange, int readStatus, String read,
            int consumeStatus, String consume) {
        if (exchange.getRequestURI().getRawPath().endsWith("/consume")) {
            FakeApi.respond(exchange, consumeStatus, consume);
        } else {
            FakeApi.respond(exchange, readStatus, read);
        }
    }

    private static long consumeCount(FakeApi api) {
        return api.requests.stream().filter(r -> r.path.endsWith("/consume")).count();
    }

    // consume

    @Test
    void consumeDecodesTheFirstConsume() throws IOException {
        try (FakeApi api = FakeApi.json(200, CONSUMED)) {
            ConsumeResult result = client(api.url()).events().consume(REQUEST_ID);

            assertEquals(REQUEST_ID, result.getRequestId());
            assertEquals("2026-11-17T10:00:00Z", result.getConsumedAt());
            assertTrue(result.isFirst());
            FakeApi.Recorded sent = api.first();
            assertEquals("POST", sent.method);
            assertEquals(CONSUME, sent.path);
            assertEquals(SECRET_KEY, sent.header("X-API-Key"));
            assertEquals(0, sent.body.length);
        }
    }

    @Test
    void consumeEscapesTheRequestId() throws IOException {
        try (FakeApi api = FakeApi.json(200, CONSUMED)) {
            client(api.url()).events().consume("a b/c");

            assertEquals("/api/v1/events/a%20b%2Fc/consume", api.first().path);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ALREADY_LITERAL, ALREADY_ENVELOPE})
    void consumeTwiceThrowsTheTypedError(String body) throws IOException {
        try (FakeApi api = FakeApi.json(409, body)) {
            EventAlreadyConsumedException e = assertThrows(EventAlreadyConsumedException.class,
                    () -> client(api.url()).events().consume(REQUEST_ID));

            assertEquals(409, e.getStatus());
            assertEquals("event_already_consumed", e.getCode());
            assertTrue(e.isEventAlreadyConsumed());
            assertEquals("2026-11-17T10:00:00Z", e.getConsumedAt());
            assertFalse(e.getMessage().contains("2026-11-17"));
            assertEquals(1, api.callCount());
        }
    }

    @Test
    void consumeAlreadyConsumedWithoutATime() throws IOException {
        try (FakeApi api = FakeApi.json(409, error(409, "event_already_consumed"))) {
            EventAlreadyConsumedException e = assertThrows(EventAlreadyConsumedException.class,
                    () -> client(api.url()).events().consume(REQUEST_ID));

            assertNull(e.getConsumedAt());
        }
    }

    @Test
    void consumeOfAnUnknownEventThrowsTheApiError() throws IOException {
        try (FakeApi api = FakeApi.json(404, error(404, "event_not_found"))) {
            FingerlyApiException e = assertThrows(FingerlyApiException.class,
                    () -> client(api.url()).events().consume(REQUEST_ID));

            assertFalse(e instanceof EventAlreadyConsumedException);
            assertTrue(e.isEventNotFound());
        }
    }

    @Test
    void consumeIsNeverRetried() throws IOException {
        try (FakeApi api = FakeApi.json(503, error(503, "unavailable"))) {
            assertThrows(FingerlyApiException.class,
                    () -> client(api.url()).events().consume(REQUEST_ID));

            assertEquals(1, api.callCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", ".."})
    void consumeRefusesABadRequestIdWithoutARequest(String requestId) throws IOException {
        try (FakeApi api = FakeApi.json(200, CONSUMED)) {
            FingerlyClient fingerly = client(api.url());
            assertThrows(IllegalArgumentException.class, () -> fingerly.events().consume(requestId));
            assertThrows(IllegalArgumentException.class, () -> fingerly.events().consume(null));

            assertEquals(0, api.callCount());
        }
    }

    // verify

    @Test
    void verifyPassesAndConsumesOnce() throws IOException {
        try (FakeApi api = api(200, event(10, GOOD), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events()
                    .verify(REQUEST_ID, VerifyOptions.builder().tag("checkout").build());

            assertTrue(result.isOk());
            assertEquals("", result.getReason());
            assertEquals("v1", result.getEvent().getVisitorId());
            assertEquals(2, api.callCount());
            assertEquals("GET", api.requests.get(0).method);
            assertEquals(EVENT, api.requests.get(0).path);
            assertEquals("POST", api.requests.get(1).method);
            assertEquals(CONSUME, api.requests.get(1).path);
            assertEquals(SECRET_KEY, api.requests.get(1).header("X-API-Key"));
        }
    }

    @Test
    void verifyLeavesScorePolicyToTheCaller() throws IOException {
        String high = "\"visitor_id\":\"v1\",\"suspect_score\":99,\"suspect_level\":\"high\","
                + "\"triggers\":[{\"signal\":\"tampering\"}]";
        try (FakeApi api = api(200, event(10, high), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events().verify(REQUEST_ID);

            assertTrue(result.isOk());
            assertEquals("high", result.getEvent().getSuspectLevel());
        }
    }

    @Test
    void verifyAsyncPasses() throws IOException {
        try (FakeApi api = api(200, event(10, GOOD), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events().verifyAsync(REQUEST_ID).join();

            assertTrue(result.isOk());
            assertEquals(1, consumeCount(api));
        }
    }

    @Test
    void verifyWithoutConsumeNeverPosts() throws IOException {
        try (FakeApi api = api(200, event(10, GOOD), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events()
                    .verify(REQUEST_ID, VerifyOptions.builder().consume(false).build());

            assertTrue(result.isOk());
            assertEquals(1, api.callCount());
            assertEquals(0, consumeCount(api));
        }
    }

    @Test
    void verifyNotFound() throws IOException {
        try (FakeApi api = api(404, error(404, "event_not_found"), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events().verify(REQUEST_ID);

            assertFalse(result.isOk());
            assertEquals(VerifyResult.REASON_NOT_FOUND, result.getReason());
            assertEquals("not_found", result.getReason());
            assertNull(result.getEvent());
            assertEquals(0, consumeCount(api));
        }
    }

    @Test
    void verifyTooOld() throws IOException {
        try (FakeApi api = api(200, event(4000, GOOD), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events().verify(REQUEST_ID);

            assertEquals("too_old", result.getReason());
            assertNotNull(result.getEvent());
            assertEquals(0, consumeCount(api));
        }
    }

    @Test
    void verifyDefaultsToFiveMinutes() throws IOException {
        try (FakeApi api = api(200, event(200, GOOD), 200, CONSUMED)) {
            assertTrue(client(api.url()).events().verify(REQUEST_ID).isOk());
        }
        try (FakeApi api = api(200, event(400, GOOD), 200, CONSUMED)) {
            assertEquals("too_old", client(api.url()).events().verify(REQUEST_ID).getReason());
            assertEquals(0, consumeCount(api));
        }
    }

    @Test
    void verifyHonoursMaxAge() throws IOException {
        try (FakeApi api = api(200, event(200, GOOD), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events()
                    .verify(REQUEST_ID, VerifyOptions.builder().maxAgeSeconds(60).build());

            assertEquals("too_old", result.getReason());
            assertEquals(0, consumeCount(api));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"yesterday\"", "\"\"", "12", "null"})
    void verifyUnreadableTimeIsTooOld(String occurredAt) throws IOException {
        try (FakeApi api = api(200, event(occurredAt, GOOD), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events().verify(REQUEST_ID);

            assertEquals("too_old", result.getReason());
            assertNotNull(result.getEvent());
            assertEquals(0, consumeCount(api));
        }
    }

    @Test
    void verifyMissingTimeIsTooOld() throws IOException {
        try (FakeApi api = api(200, event(null, GOOD), 200, CONSUMED)) {
            assertEquals("too_old", client(api.url()).events().verify(REQUEST_ID).getReason());
            assertEquals(0, consumeCount(api));
        }
    }

    @Test
    void verifyFutureTimePasses() throws IOException {
        try (FakeApi api = api(200, event(-600, GOOD), 200, CONSUMED)) {
            assertTrue(client(api.url()).events().verify(REQUEST_ID).isOk());
        }
    }

    @Test
    void verifyTagMismatch() throws IOException {
        try (FakeApi api = api(200, event(10, GOOD), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events()
                    .verify(REQUEST_ID, VerifyOptions.builder().tag("login").build());

            assertEquals("tag_mismatch", result.getReason());
            assertEquals(0, consumeCount(api));
        }
    }

    @Test
    void verifyUntaggedEventMismatchesAnyTag() throws IOException {
        String untagged = "\"visitor_id\":\"v1\",\"suspect_score\":12";
        try (FakeApi api = api(200, event(10, untagged), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events()
                    .verify(REQUEST_ID, VerifyOptions.builder().tag("checkout").build());

            assertEquals("tag_mismatch", result.getReason());
            assertEquals(0, consumeCount(api));
        }
    }

    @Test
    void verifyWithoutATagSkipsTheTagCheck() throws IOException {
        try (FakeApi api = api(200, event(10, GOOD), 200, CONSUMED)) {
            assertTrue(client(api.url()).events().verify(REQUEST_ID).isOk());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"visitor_id\":\"\",\"suspect_score\":12", "\"suspect_score\":12"})
    void verifyUnidentifiable(String members) throws IOException {
        try (FakeApi api = api(200, event(10, members), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events().verify(REQUEST_ID);

            assertEquals("unidentifiable", result.getReason());
            assertEquals(0, consumeCount(api));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"visitor_id\":\"v1\",\"suspect_score\":null", "\"visitor_id\":\"v1\""})
    void verifyUnscored(String members) throws IOException {
        try (FakeApi api = api(200, event(10, members), 200, CONSUMED)) {
            VerifyResult result = client(api.url()).events().verify(REQUEST_ID);

            assertEquals("unscored", result.getReason());
            assertEquals(0, consumeCount(api));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ALREADY_LITERAL, ALREADY_ENVELOPE})
    void verifyAlreadyConsumed(String body) throws IOException {
        try (FakeApi api = api(200, event(10, GOOD), 409, body)) {
            VerifyResult result = client(api.url()).events().verify(REQUEST_ID);

            assertFalse(result.isOk());
            assertEquals("already_consumed", result.getReason());
            assertEquals("v1", result.getEvent().getVisitorId());
            assertEquals(1, consumeCount(api));
        }
    }

    @Test
    void verifyNotFoundOnConsume() throws IOException {
        try (FakeApi api = api(200, event(10, GOOD), 404, error(404, "event_not_found"))) {
            VerifyResult result = client(api.url()).events().verify(REQUEST_ID);

            assertEquals("not_found", result.getReason());
            assertNull(result.getEvent());
        }
    }

    @Test
    void verifyRaisesOtherConsumeErrors() throws IOException {
        try (FakeApi api = api(200, event(10, GOOD), 500, error(500, "internal"))) {
            FingerlyApiException e = assertThrows(FingerlyApiException.class,
                    () -> client(api.url()).events().verify(REQUEST_ID));

            assertEquals(500, e.getStatus());
            assertEquals(1, consumeCount(api));
        }
    }

    @Test
    void verifyChecksInOrder() throws IOException {
        String bad = "\"visitor_id\":\"\",\"tag\":\"other\"";
        VerifyOptions tagged = VerifyOptions.builder().tag("checkout").build();
        try (FakeApi api = api(200, event(4000, bad), 200, CONSUMED)) {
            assertEquals("too_old", client(api.url()).events().verify(REQUEST_ID, tagged).getReason());
            assertEquals(0, consumeCount(api));
        }
        try (FakeApi api = api(200, event(10, bad), 200, CONSUMED)) {
            assertEquals("tag_mismatch", client(api.url()).events().verify(REQUEST_ID, tagged).getReason());
            assertEquals(0, consumeCount(api));
        }
        try (FakeApi api = api(200, event(10, bad), 200, CONSUMED)) {
            assertEquals("unidentifiable", client(api.url()).events().verify(REQUEST_ID).getReason());
            assertEquals(0, consumeCount(api));
        }
    }

    @Test
    void verifyRaisesAPendingRead() throws IOException {
        try (FakeApi api = FakeApi.start(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "2");
            FakeApi.respond(exchange, 404, error(404, "event_pending"));
        })) {
            FingerlyApiException e = assertThrows(FingerlyApiException.class,
                    () -> client(api.url()).events().verify(REQUEST_ID));

            assertTrue(e.isPending());
            assertEquals(Integer.valueOf(2), e.getRetryAfter());
            assertEquals(0, consumeCount(api));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500, 503})
    void verifyRaisesOtherReadErrors(int status) throws IOException {
        try (FakeApi api = api(status, error(status, "e"), 200, CONSUMED)) {
            FingerlyApiException e = assertThrows(FingerlyApiException.class,
                    () -> client(api.url()).events().verify(REQUEST_ID));

            assertEquals(status, e.getStatus());
            assertEquals(1, api.callCount());
        }
    }

    @Test
    void verifyAsyncRaisesTheSameErrors() throws IOException {
        try (FakeApi api = api(404, error(404, "event_pending"), 200, CONSUMED)) {
            CompletionException e = assertThrows(CompletionException.class,
                    () -> client(api.url()).events().verifyAsync(REQUEST_ID).join());

            assertInstanceOf(FingerlyApiException.class, e.getCause());
        }
    }

    @Test
    void verifyRefusesBadInputBeforeAnyRequest() throws IOException {
        try (FakeApi api = api(200, event(10, GOOD), 200, CONSUMED)) {
            FingerlyClient fingerly = client(api.url());
            assertThrows(IllegalArgumentException.class, () -> fingerly.events().verify(""));
            assertThrows(IllegalArgumentException.class, () -> fingerly.events().verify(".."));
            assertThrows(IllegalArgumentException.class, () -> fingerly.events().verifyAsync("."));

            assertEquals(0, api.callCount());
        }
    }

    @Test
    void verifyOptionsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> VerifyOptions.builder().maxAgeSeconds(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> VerifyOptions.builder().maxAgeSeconds(-5).build());
        assertThrows(IllegalArgumentException.class,
                () -> VerifyOptions.builder().tag("").build());
    }

    @Test
    void verifyOptionsDefaults() {
        VerifyOptions defaults = VerifyOptions.defaults();

        assertEquals(300, defaults.getMaxAgeSeconds());
        assertNull(defaults.getTag());
        assertTrue(defaults.isConsume());
        assertSame(defaults, VerifyOptions.defaults());
    }
}
