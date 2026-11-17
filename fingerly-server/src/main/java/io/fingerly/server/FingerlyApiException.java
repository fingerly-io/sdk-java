package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Thrown for any non-2xx response from the Fingerly API.
 *
 * <p>The message carries only the HTTP status. Response bodies are never
 * echoed, and any occurrence of the secret key in the metadata is redacted.
 *
 * <pre>{@code
 * try {
 *     event = fingerly.events().get(requestId);
 * } catch (FingerlyApiException e) {
 *     if (e.isPending()) return "retry"; // not recorded yet: retry after e.getRetryAfter()
 *     if (e.isEventNotFound()) return "refuse";
 *     throw e;
 * }
 * }</pre>
 */
public class FingerlyApiException extends FingerlyException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final transient JsonNode details;
    private final String requestId;
    private final Integer retryAfter;

    FingerlyApiException(
            int status, String code, JsonNode details, String requestId, Integer retryAfter) {
        super(message(status, code));
        this.status = status;
        this.code = code;
        this.details = details;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
    }

    private static String message(int status, String code) {
        return code == null || code.isEmpty()
                ? "Fingerly API returned HTTP " + status
                : "Fingerly API returned HTTP " + status + " (" + code + ")";
    }

    /** The HTTP status code. */
    public int getStatus() {
        return status;
    }

    /**
     * The stable machine-readable code, such as {@code event_not_found}, or
     * null when the response carried none.
     */
    public String getCode() {
        return code;
    }

    /**
     * Extra context from the error body, or null when there was none. Its
     * shape depends on {@link #getCode()}.
     */
    public JsonNode getDetails() {
        return details;
    }

    /** {@link #getDetails()} as JSON text, or null; safe to hand to Jackson 3. */
    public String getDetailsJson() {
        return details == null ? null : details.toString();
    }

    /** The server-side trace id to quote in support requests, or null. */
    public String getRequestId() {
        return requestId;
    }

    /**
     * The seconds the API asked you to wait before trying again, from
     * {@code Retry-After} (an HTTP date is turned into seconds from now), or
     * null when it sent none. The SDK never retries by itself; a 429 carries
     * this so you can.
     */
    public Integer getRetryAfter() {
        return retryAfter;
    }

    /**
     * Reports whether this is a read for an event that does not exist: a
     * request id that was never issued, or one past retention.
     *
     * <p>It is false for {@link #isPending()}: that event exists and is
     * still being recorded.
     */
    public boolean isEventNotFound() {
        return status == 404 && "event_not_found".equals(code);
    }

    /**
     * Reports whether this is a read for an event the API accepted but has
     * not recorded yet ({@code 404 event_pending}).
     *
     * <p>Nothing is decided yet: read again after {@link #getRetryAfter()}
     * seconds, or verify asynchronously. The SDK never retries by itself.
     */
    public boolean isPending() {
        return status == 404 && "event_pending".equals(code);
    }

    /**
     * Reports whether a consume found the event already consumed
     * ({@code 409 event_already_consumed}); such an answer arrives as an
     * {@link EventAlreadyConsumedException}.
     */
    public boolean isEventAlreadyConsumed() {
        return status == 409 && "event_already_consumed".equals(code);
    }

    /**
     * {@code 404 device_check_not_held}: no DeviceCheck token is held for the
     * event. Decide without the bits.
     */
    public boolean isDeviceCheckNotHeld() {
        return status == 404 && "device_check_not_held".equals(code);
    }

    /**
     * {@code 409 device_check_spent}: the event's token was already used, or
     * this call is not the request the token was first used for.
     */
    public boolean isDeviceCheckSpent() {
        return status == 409 && "device_check_spent".equals(code);
    }

    /** {@code 409 device_check_busy}: another call is using the token right now. */
    public boolean isDeviceCheckBusy() {
        return status == 409 && "device_check_busy".equals(code);
    }

    /**
     * {@code 409 credential_unavailable}: the project's Apple key is missing,
     * refused by Apple, or has DeviceCheck turned off. The token is kept.
     */
    public boolean isCredentialUnavailable() {
        return status == 409 && "credential_unavailable".equals(code);
    }

    /** {@code 422 bits_required}: a DeviceCheck write named neither bit. */
    public boolean isBitsRequired() {
        return status == 422 && "bits_required".equals(code);
    }

    /**
     * {@code 422 apple_rejected}: Apple rejected the DeviceCheck token, which
     * is now used up. {@link #getAppleStatus()} holds Apple's HTTP status.
     */
    public boolean isAppleRejected() {
        return status == 422 && "apple_rejected".equals(code);
    }

    /**
     * {@code 503 device_check_unavailable}: Apple or storage could not
     * answer. The token is kept for a retry of the same request within the hour.
     */
    public boolean isDeviceCheckUnavailable() {
        return status == 503 && "device_check_unavailable".equals(code);
    }

    /**
     * Apple's HTTP status on an {@code apple_rejected} answer, from the
     * {@code apple_status} entry of the error details, or null when there is none.
     */
    public Integer getAppleStatus() {
        if (details == null || !details.isArray()) {
            return null;
        }
        for (JsonNode detail : details) {
            if ("apple_status".equals(detail.path("field").asText())) {
                String issue = detail.path("issue").asText();
                return issue.matches("[0-9]{1,9}") ? Integer.valueOf(issue) : null;
            }
        }
        return null;
    }
}
