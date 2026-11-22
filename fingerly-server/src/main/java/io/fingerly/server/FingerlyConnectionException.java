package io.fingerly.server;

/**
 * Thrown when the Fingerly API could not be reached at all.
 *
 * <p>A DNS failure, a refused connection or a TLS error arrives here. The
 * originating exception is kept as the cause.
 */
public class FingerlyConnectionException extends FingerlyException {

    private static final long serialVersionUID = 1L;

    /** Whether the request had been written in full before the connection failed. */
    private final boolean requestWritten;

    public FingerlyConnectionException(String message, Throwable cause) {
        this(message, cause, false);
    }

    FingerlyConnectionException(String message, Throwable cause, boolean requestWritten) {
        super(message, cause);
        this.requestWritten = requestWritten;
    }

    /**
     * True when the connection closed, reset or cut the answer short after the
     * whole request was written, so the API may have acted on it.
     */
    boolean requestWritten() {
        return requestWritten;
    }
}
