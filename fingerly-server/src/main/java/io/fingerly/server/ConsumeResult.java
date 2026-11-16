package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;

/** The answer to the first consume of an event. */
public final class ConsumeResult {

    private final String requestId;
    private final String consumedAt;
    private final boolean first;

    private ConsumeResult(String requestId, String consumedAt, boolean first) {
        this.requestId = requestId;
        this.consumedAt = consumedAt;
        this.first = first;
    }

    static ConsumeResult from(JsonNode tree) {
        return new ConsumeResult(
                tree.path("request_id").isTextual() ? tree.path("request_id").asText() : null,
                tree.path("consumed_at").isTextual() ? tree.path("consumed_at").asText() : null,
                tree.path("first").asBoolean(false));
    }

    /** The consumed event's request id. */
    public String getRequestId() {
        return requestId;
    }

    /** When the event was consumed, an RFC 3339 timestamp as the API sent it. */
    public String getConsumedAt() {
        return consumedAt;
    }

    /** True when this call consumed the event. */
    public boolean isFirst() {
        return first;
    }

    @Override
    public String toString() {
        return "ConsumeResult[requestId=" + requestId + ", consumedAt=" + consumedAt
                + ", first=" + first + "]";
    }
}
