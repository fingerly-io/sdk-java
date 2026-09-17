package io.fingerly.server;

/**
 * Thrown when an operation does not finish within its time limit.
 *
 * <p>The deadline covers reading the response body, not only the headers.
 */
public class FingerlyTimeoutException extends FingerlyException {

    private static final long serialVersionUID = 1L;

    public FingerlyTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
