package io.fingerly.server;

/**
 * Thrown when the Fingerly API could not be reached at all.
 *
 * <p>A DNS failure, a refused connection or a TLS error arrives here. The
 * originating exception is kept as the cause.
 */
public class FingerlyConnectionException extends FingerlyException {

    private static final long serialVersionUID = 1L;

    public FingerlyConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
