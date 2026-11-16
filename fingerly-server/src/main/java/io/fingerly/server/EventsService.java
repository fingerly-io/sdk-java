package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

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
     * @throws FingerlyApiException on any non-2xx response.
     *     {@code 404 event_pending} ({@link FingerlyApiException#isPending()})
     *     means the event was accepted and is still being recorded: retry
     *     after {@link FingerlyApiException#getRetryAfter()} seconds.
     *     {@code 404 event_not_found} means a request id that was never
     *     issued, or one past retention.
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

    /**
     * Consumes an event, once: the first call succeeds and every later one
     * throws {@link EventAlreadyConsumedException}, so one event backs one
     * decision. Never retried.
     *
     * @param requestId the {@code request_id} returned by identification
     * @throws EventAlreadyConsumedException on {@code 409 event_already_consumed},
     *     with {@link EventAlreadyConsumedException#getConsumedAt()}
     * @throws FingerlyApiException on any other non-2xx response, such as
     *     {@code 404 event_not_found}
     * @throws FingerlyTimeoutException if the request exceeds the client timeout
     * @throws IllegalArgumentException if requestId is null, empty, "." or ".."
     */
    public ConsumeResult consume(String requestId) {
        return client.await(consumeAsync(requestId));
    }

    /** {@link #consume} without holding a thread; see {@link #getAsync}. */
    public CompletableFuture<ConsumeResult> consumeAsync(String requestId) {
        return client.sendAsync("POST", eventPath(requestId) + "/consume", null)
                .thenApply(body -> ConsumeResult.from(Json.tree(body)));
    }

    /** {@link #verify(String, VerifyOptions)} with {@link VerifyOptions#defaults()}. */
    public VerifyResult verify(String requestId) {
        return verify(requestId, null);
    }

    /**
     * Reads an event and checks, in this order, stopping at the first that
     * fails: it exists ({@code not_found}), it is no older than
     * {@code maxAgeSeconds} ({@code too_old}, also when {@code occurred_at}
     * is missing or unreadable), it carries the expected tag when one is set
     * ({@code tag_mismatch}), it was identifiable ({@code unidentifiable})
     * and scored ({@code unscored}). When every check passes and consuming
     * is on, it then consumes the event ({@code already_consumed} when
     * another call did first).
     *
     * <p>The suspect score, level and triggers are never looked at: apply
     * your own policy to them after {@link VerifyResult#isOk()}.
     *
     * @param options the checks, or null for {@link VerifyOptions#defaults()}
     * @throws FingerlyApiException on any answer other than
     *     {@code 404 event_not_found} and {@code 409 event_already_consumed},
     *     including {@code 404 event_pending}, which is not a verdict: read
     *     again after {@link FingerlyApiException#getRetryAfter()} seconds
     * @throws FingerlyTimeoutException if a request exceeds the client timeout
     * @throws IllegalArgumentException if requestId is null, empty, "." or ".."
     */
    public VerifyResult verify(String requestId, VerifyOptions options) {
        return client.await(verifyAsync(requestId, options));
    }

    /** {@link #verify(String)} without holding a thread. */
    public CompletableFuture<VerifyResult> verifyAsync(String requestId) {
        return verifyAsync(requestId, null);
    }

    /**
     * {@link #verify(String, VerifyOptions)} without holding a thread.
     * Cancelling the future abandons the call in flight.
     */
    public CompletableFuture<VerifyResult> verifyAsync(String requestId, VerifyOptions options) {
        VerifyOptions checks = options != null ? options : VerifyOptions.defaults();
        String path = eventPath(requestId);
        CompletableFuture<VerifyResult> result = new CompletableFuture<>();
        AtomicReference<Future<?>> current = new AtomicReference<>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                Future<?> inFlight = current.get();
                if (inFlight != null) {
                    inFlight.cancel(true);
                }
            }
        });

        CompletableFuture<byte[]> read = client.getAsync(path);
        current.set(read);
        read.whenComplete((body, failure) -> {
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                Throwable cause = unwrap(failure);
                if (cause instanceof FingerlyApiException
                        && ((FingerlyApiException) cause).isEventNotFound()) {
                    result.complete(VerifyResult.failed(VerifyResult.REASON_NOT_FOUND, null));
                } else {
                    result.completeExceptionally(cause);
                }
                return;
            }
            Event event;
            String reason;
            try {
                event = parseVerifiedEvent(body);
                reason = check(event, checks);
            } catch (RuntimeException unreadable) {
                result.completeExceptionally(unreadable);
                return;
            }
            if (reason != null) {
                result.complete(VerifyResult.failed(reason, event));
                return;
            }
            if (!checks.isConsume()) {
                result.complete(VerifyResult.ok(event));
                return;
            }
            CompletableFuture<byte[]> consumed = client.sendAsync("POST", path + "/consume", null);
            current.set(consumed);
            if (result.isCancelled()) {
                consumed.cancel(true);
                return;
            }
            consumed.whenComplete((ignored, consumeFailure) -> {
                if (consumeFailure == null) {
                    result.complete(VerifyResult.ok(event));
                    return;
                }
                Throwable cause = unwrap(consumeFailure);
                if (cause instanceof EventAlreadyConsumedException) {
                    result.complete(VerifyResult.failed(VerifyResult.REASON_ALREADY_CONSUMED, event));
                } else if (cause instanceof FingerlyApiException
                        && ((FingerlyApiException) cause).isEventNotFound()) {
                    result.complete(VerifyResult.failed(VerifyResult.REASON_NOT_FOUND, null));
                } else {
                    result.completeExceptionally(cause);
                }
            });
        });
        return result;
    }

    /** The first check before consumption that fails, or null when all pass. */
    private static String check(Event event, VerifyOptions checks) {
        Instant occurred = rfc3339(event.getRaw().path("occurred_at"));
        if (occurred == null || Duration.between(occurred, Instant.now())
                .compareTo(Duration.ofSeconds(checks.getMaxAgeSeconds())) > 0) {
            return VerifyResult.REASON_TOO_OLD;
        }
        if (checks.getTag() != null) {
            String tag = event.getTag() == null ? "" : event.getTag();
            if (!tag.equals(checks.getTag())) {
                return VerifyResult.REASON_TAG_MISMATCH;
            }
        }
        if (event.getVisitorId() == null || event.getVisitorId().isEmpty()) {
            return VerifyResult.REASON_UNIDENTIFIABLE;
        }
        if (!event.isScored()) {
            return VerifyResult.REASON_UNSCORED;
        }
        return null;
    }

    /** Reads an RFC 3339 timestamp, or null when it is missing or unreadable. */
    private static Instant rfc3339(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /**
     * Parses an event for {@link #verifyAsync(String, VerifyOptions)}, where
     * an unreadable {@code occurred_at} fails the age check rather than the
     * read. {@link Event#getRaw()} keeps the body as sent.
     */
    private static Event parseVerifiedEvent(byte[] body) {
        JsonNode tree = Json.tree(body);
        JsonNode bound = tree;
        if (tree.isObject() && tree.has("occurred_at")) {
            JsonNode occurred = tree.get("occurred_at");
            boolean readable;
            try {
                readable = occurred.isNull() || occurred.isTextual()
                        && (occurred.asText().isEmpty() || Instant.parse(occurred.asText()) != null);
            } catch (DateTimeParseException unreadable) {
                readable = false;
            }
            if (!readable) {
                bound = tree.deepCopy();
                ((ObjectNode) bound).remove("occurred_at");
            }
        }
        Event event = Json.convert(bound, Event.class);
        event.setRaw(tree, new String(body, StandardCharsets.UTF_8));
        return event;
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
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

    @Override
    public String toString() {
        return "EventsService[" + client + "]";
    }
}
