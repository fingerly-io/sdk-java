package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reads events stored for the organization and environment of the client's
 * secret key. Reached as {@code fingerly.events()}.
 */
public final class EventsService {

    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);

    private final FingerlyClient client;

    EventsService(FingerlyClient client) {
        this.client = client;
    }

    /**
     * Fetches one event by request id.
     *
     * @param requestId the {@code request_id} returned by identification
     * @throws FingerlyApiException on any non-2xx response. An event that has
     *     not been archived yet gives {@code 404 event_not_found}; use
     *     {@link #waitFor} right after identifying a visitor.
     * @throws FingerlyTimeoutException if the request exceeds the client timeout
     */
    public Event get(String requestId) {
        JsonNode tree = client.get("/events/" + encodeSegment(requestId));
        Event event = Json.convert(tree, Event.class);
        event.setRaw(tree);
        return event;
    }

    /**
     * Lists events within the retention window, newest first.
     *
     * @param params the filters; null leaves every one unset
     * @throws FingerlyApiException on any non-2xx response
     */
    public EventPage list(EventListParams params) {
        String query = params == null ? "" : params.query();
        JsonNode tree = client.get("/events" + query);
        EventPage page = Json.convert(tree, EventPage.class);
        page.setRaw(tree);
        return page;
    }

    /** Lists events with no filters, so the server applies its own defaults. */
    public EventPage list() {
        return list(null);
    }

    /**
     * Polls until a newly identified event becomes readable, using the default
     * bounds: ten seconds, every 250 milliseconds.
     */
    public Event waitFor(String requestId) {
        return waitFor(requestId, DEFAULT_WAIT_TIMEOUT, DEFAULT_POLL_INTERVAL);
    }

    /**
     * Polls until a newly identified event becomes readable.
     *
     * <p>Identification answers before the event is archived, so reach for
     * this only when you have just identified a visitor. Only a structured
     * {@code 404 event_not_found} is retried; every other failure is thrown at
     * once.
     *
     * @param requestId the {@code request_id} returned by identification
     * @param timeout the total time to keep polling
     * @param interval the delay between polls
     * @throws FingerlyTimeoutException if the event is still missing at the
     *     deadline. The last {@link FingerlyApiException} is kept as the
     *     cause, so the reason it kept waiting is not lost.
     * @throws IllegalArgumentException if a bound is not positive
     */
    public Event waitFor(String requestId, Duration timeout, Duration interval) {
        Keys.positive(timeout, "timeout");
        Keys.positive(interval, "interval");

        long deadline = System.nanoTime() + timeout.toNanos();
        FingerlyApiException lastMiss = null;
        while (true) {
            try {
                return get(requestId);
            } catch (FingerlyApiException failure) {
                if (!failure.isEventNotFound()) {
                    throw failure;
                }
                lastMiss = failure;
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= interval.toNanos()) {
                throw new FingerlyTimeoutException(
                        "Timed out waiting for event " + requestId, lastMiss);
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException cause) {
                Thread.currentThread().interrupt();
                throw new FingerlyException("Interrupted while waiting for the event", cause);
            }
        }
    }

    /** Encodes a request id into exactly one path segment. */
    private static String encodeSegment(String value) {
        // URLEncoder targets form bodies, where a space is "+"; a path segment
        // needs "%20".
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
