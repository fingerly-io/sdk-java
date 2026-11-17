package io.fingerly.server;

/**
 * The server's verdict on a native event's hardware evidence, as the exact
 * strings of {@code attestation.state} in {@link Event#getDocument()}.
 *
 * <pre>{@code
 * String state = event.getDocument().path("attestation").path("state").asText();
 * if (AttestationState.FAILED.equals(state)) {
 *     return "refuse";
 * }
 * }</pre>
 */
public final class AttestationState {

    private AttestationState() {}

    /** The hardware evidence verified. */
    public static final String ATTESTED = "attested";

    /** No evidence that could be proven was offered. */
    public static final String NOT_ATTESTED = "not_attested";

    /** The evidence failed verification. */
    public static final String FAILED = "failed";
}
