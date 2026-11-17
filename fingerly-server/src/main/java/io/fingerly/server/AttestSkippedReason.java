package io.fingerly.server;

/**
 * Why a native SDK sent no attestation, as the exact strings of
 * {@code attestation.attest_skipped} in {@link Event#getDocument()}.
 * The app reports it about itself; it is diagnostic only and never changes
 * the score.
 */
public final class AttestSkippedReason {

    private AttestSkippedReason() {}

    /** Attestation was not offered. */
    public static final String NOT_OFFERED = "not_offered";

    /** Attestation is not supported on the device. */
    public static final String UNSUPPORTED = "unsupported";

    /** No attestation challenge was available. */
    public static final String CHALLENGE_UNAVAILABLE = "challenge_unavailable";

    /** The attestation key failed. */
    public static final String KEY_ERROR = "key_error";

    /** Attestation timed out. */
    public static final String TIMEOUT = "timeout";

    /** The SDK was backing off from attestation. */
    public static final String BACKOFF = "backoff";

    /** The install ID was unavailable. */
    public static final String INSTALL_UNAVAILABLE = "install_unavailable";
}
