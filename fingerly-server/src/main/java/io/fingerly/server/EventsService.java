package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Reads events stored for the organization and environment of the client's
 * secret key. Reached as {@code fingerly.events()}.
 */
public final class EventsService {

    private final FingerlyClient client;

    EventsService(FingerlyClient client) {
        this.client = client;
    }

    /**
     * Fetches one event by request id.
     *
     * @param requestId the {@code request_id} returned by identification
     * @throws FingerlyApiException on any non-2xx response. An event is
     *     readable as soon as identification answers, so
     *     {@code 404 event_not_found} means a request id that was never
     *     issued.
     * @throws FingerlyTimeoutException if the request exceeds the client timeout
     * @throws IllegalArgumentException if requestId is null, empty, "." or ".."
     */
    public Event get(String requestId) {
        if (requestId == null || requestId.isEmpty()
                || requestId.equals(".") || requestId.equals("..")) {
            // "." and ".." would be dot segments in the path, read as
            // "/events" or "/" rather than as an id.
            throw new IllegalArgumentException("requestId is required");
        }
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

    /** Encodes a request id into exactly one path segment. */
    private static String encodeSegment(String value) {
        // URLEncoder targets form bodies, where a space is "+"; a path segment
        // needs "%20".
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
