package io.fingerly.server;

import static io.fingerly.server.Fixtures.REQUEST_ID;
import static io.fingerly.server.Fixtures.SECRET_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventsServiceTest {

    private static FingerlyClient client(FakeApi api) {
        return FingerlyClient.builder().secretKey(SECRET_KEY).endpoint(api.url()).build();
    }

    @Test
    void decodesAnEventAndKeepsTheRawBody() throws IOException {
        String extra = "\"tag\":\"checkout:8412\","
                + "\"suspect_score\":37,"
                + "\"suspect_level\":\"high\","
                + "\"future_field\":\"kept\",";
        try (FakeApi api = FakeApi.json(200, Fixtures.event(extra))) {
            Event event = client(api).events().get(REQUEST_ID);

            assertEquals("checkout:8412", event.getTag());
            assertEquals("high", event.getSuspectLevel());
            assertEquals(Optional.of(37), event.getSuspectScore());
            assertTrue(event.isScored());
            // A field added after this SDK shipped is still reachable.
            assertEquals("kept", event.getRaw().path("future_field").asText());
        }
    }

    @Test
    void decodesTriggers() throws IOException {
        String extra = "\"triggers\":[{\"signal\":\"tor\",\"weight\":14,\"confidence\":\"high\"}],";
        // The fixture's own empty triggers come after, so this one wins.
        String body = Fixtures.event(extra).replace("\"triggers\":[],", "");
        try (FakeApi api = FakeApi.json(200, body)) {
            Event event = client(api).events().get(REQUEST_ID);

            assertEquals(1, event.getTriggers().size());
            assertEquals("tor", event.getTriggers().get(0).getSignal());
            assertEquals(14, event.getTriggers().get(0).getWeight());
            assertEquals("high", event.getTriggers().get(0).getConfidence());
        }
    }

    @Test
    void unscoredIsNotZero() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.event("\"suspect_score\":null,"))) {
            Event event = client(api).events().get(REQUEST_ID);
            assertFalse(event.isScored());
            assertEquals(Optional.empty(), event.getSuspectScore());
        }
        try (FakeApi api = FakeApi.json(200, Fixtures.event("\"suspect_score\":0,"))) {
            Event event = client(api).events().get(REQUEST_ID);
            assertTrue(event.isScored(), "a zero score is still a score");
            assertEquals(Optional.of(0), event.getSuspectScore());
        }
    }

    @Test
    void triggersAreNeverNull() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.event().replace(",\"triggers\":[]", ""))) {
            Event event = client(api).events().get(REQUEST_ID);
            assertTrue(event.getTriggers().isEmpty());
        }
    }

    @Test
    void encodesEveryFilter() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.page())) {
            Instant from = Instant.parse("2026-09-01T08:30:00Z");
            client(api).events().list(EventListParams.builder()
                    .from(from)
                    .to(from.plus(Duration.ofDays(1)))
                    .page(2)
                    .limit(20)
                    .visitor("visitor-test")
                    .level("high")
                    .build());

            String query = api.first().query;
            assertTrue(query.contains("from=2026-09-01T08%3A30%3A00Z"), query);
            assertTrue(query.contains("to=2026-09-02T08%3A30%3A00Z"), query);
            assertTrue(query.contains("page=2"), query);
            assertTrue(query.contains("limit=20"), query);
            assertTrue(query.contains("visitor=visitor-test"), query);
            assertTrue(query.contains("level=high"), query);
        }
    }

    @Test
    void omitsUnsetFilters() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.page())) {
            FingerlyClient fingerly = client(api);
            fingerly.events().list();
            fingerly.events().list(EventListParams.builder().build());

            for (FakeApi.Recorded request : api.requests) {
                assertEquals("/api/v1/events", request.path);
                assertEquals(null, request.query);
            }
        }
    }

    @Test
    void sendsWindowBoundsAsRfc3339InUtc() throws IOException {
        try (FakeApi api = FakeApi.json(200, Fixtures.page())) {
            // An instant carries no zone, so the rendering must still be UTC.
            client(api).events().list(EventListParams.builder()
                    .from(Instant.parse("2026-09-01T08:30:00Z"))
                    .build());
            assertTrue(api.first().query.contains("2026-09-01T08%3A30%3A00Z"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsUnusablePaging(int value) {
        assertThrows(IllegalArgumentException.class,
                () -> EventListParams.builder().page(value).build());
        assertThrows(IllegalArgumentException.class,
                () -> EventListParams.builder().limit(value).build());
    }

    /**
     * An event is readable as soon as identification answers, so a missing one
     * means a request id that was never issued: reported at once, in one
     * request, rather than waited through.
     */
    @Test
    void reportsAMissingEventWithoutRetrying() throws IOException {
        try (FakeApi api = FakeApi.json(404, Fixtures.error("event_not_found", 404))) {
            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).events().get(REQUEST_ID));

            assertEquals(404, failure.getStatus());
            assertTrue(failure.isEventNotFound());
            assertEquals(1, api.callCount());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500})
    void propagatesEveryOtherFailure(int status) throws IOException {
        try (FakeApi api = FakeApi.json(status, Fixtures.error("other", status))) {
            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).events().get(REQUEST_ID));

            assertEquals(status, failure.getStatus());
            assertEquals(1, api.callCount());
        }
    }

    @Test
    void pageExposesItsShape() throws IOException {
        String body = "{\"rows\":[" + Fixtures.event() + "],\"page\":2,\"page_size\":20}";
        try (FakeApi api = FakeApi.json(200, body)) {
            EventPage page = client(api).events().list();

            assertEquals(2, page.getPage());
            assertEquals(20, page.getPageSize());
            assertEquals(1, page.getRows().size());
            assertEquals(REQUEST_ID, page.getRows().get(0).getRequestId());
            assertSame(page.getRaw(), page.getRaw());
        }
    }
}
