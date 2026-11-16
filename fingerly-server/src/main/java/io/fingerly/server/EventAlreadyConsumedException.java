package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Thrown by {@link EventsService#consume} when the event was already
 * consumed: {@code 409 event_already_consumed}. Another call spent the event
 * first, so it must not back a second decision.
 */
public final class EventAlreadyConsumedException extends FingerlyApiException {

    private static final long serialVersionUID = 1L;

    private final String consumedAt;

    EventAlreadyConsumedException(
            int status, String code, JsonNode details, String requestId, Integer retryAfter,
            String consumedAt) {
        super(status, code, details, requestId, retryAfter);
        this.consumedAt = consumedAt;
    }

    /**
     * When the event was first consumed, an RFC 3339 timestamp as the API
     * sent it, or null when the answer carried none.
     */
    public String getConsumedAt() {
        return consumedAt;
    }
}
