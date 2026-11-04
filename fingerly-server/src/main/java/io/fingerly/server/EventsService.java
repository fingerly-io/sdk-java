package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

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
        return parseEvent(client.get(eventPath(requestId)));
    }

    /**
     * Reads one event without holding a thread, for reactive and other
     * asynchronous code: {@link #get} blocks its caller until the API
     * answers, which must never happen on an event loop.
     *
     * @return completes with the event, or exceptionally with the same
     *     {@link FingerlyException} subclasses {@link #get} throws
     * @throws IllegalArgumentException if requestId is null, empty, "." or ".."
     */
    public CompletableFuture<Event> getAsync(String requestId) {
        return client.getAsync(eventPath(requestId)).thenApply(EventsService::parseEvent);
    }

    /**
     * Lists events within the retention window, newest first.
     *
     * @param params the filters; null leaves every one unset
     * @throws FingerlyApiException on any non-2xx response
     */
    public EventPage list(EventListParams params) {
        return parsePage(client.get(listPath(params)));
    }

    /**
     * Lists events without holding a thread; see {@link #getAsync}.
     *
     * @param params the filters, or null for none
     */
    public CompletableFuture<EventPage> listAsync(EventListParams params) {
        return client.getAsync(listPath(params)).thenApply(EventsService::parsePage);
    }

    /** Lists events with no filters, so the server applies its own defaults. */
    public EventPage list() {
        return list(null);
    }

    private static String eventPath(String requestId) {
        if (requestId == null || requestId.isEmpty()
                || requestId.equals(".") || requestId.equals("..")) {
            // "." and ".." would be dot segments in the path, read as
            // "/events" or "/" rather than as an id.
            throw new IllegalArgumentException("requestId is required");
        }
        return "/events/" + encodeSegment(requestId);
    }

    private static String listPath(EventListParams params) {
        return "/events" + (params == null ? "" : params.query());
    }

    private static Event parseEvent(byte[] body) {
        JsonNode tree = Json.tree(body);
        Event event = Json.convert(tree, Event.class);
        event.setRaw(tree, new String(body, StandardCharsets.UTF_8));
        return event;
    }

    private static EventPage parsePage(byte[] body) {
        JsonNode tree = Json.tree(body);
        EventPage page = Json.convert(tree, EventPage.class);
        page.setRaw(tree, new String(body, StandardCharsets.UTF_8));
        return page;
    }

    private static String encodeSegment(String value) {
        // URLEncoder targets form bodies, where a space is "+"; a path segment
        // needs "%20".
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
