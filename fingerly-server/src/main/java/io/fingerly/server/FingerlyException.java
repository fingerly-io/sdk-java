package io.fingerly.server;

/**
 * Base class for every exception this SDK raises.
 *
 * <p>Unchecked, so reading an event does not force a {@code throws} clause
 * through the call sites that only pass the result along.
 */
public class FingerlyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FingerlyException(String message) {
        super(message);
    }

    public FingerlyException(String message, Throwable cause) {
        super(message, cause);
    }
}
