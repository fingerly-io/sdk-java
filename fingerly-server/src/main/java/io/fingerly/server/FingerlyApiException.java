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
 *     if (e.getStatus() == 404) return "refuse";
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

    FingerlyApiException(int status, String code, JsonNode details, String requestId) {
        super(message(status, code));
        this.status = status;
        this.code = code;
        this.details = details;
        this.requestId = requestId;
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

    /** The server-side trace id to quote in support requests, or null. */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Reports whether this is a read for an event that does not exist.
     *
     * <p>One is readable as soon as identification answers, so this means a
     * request id that was never issued rather than one that has not landed.
     */
    public boolean isEventNotFound() {
        return status == 404 && "event_not_found".equals(code);
    }
}
