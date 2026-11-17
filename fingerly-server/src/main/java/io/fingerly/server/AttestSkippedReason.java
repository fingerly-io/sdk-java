package io.fingerly.server;

/**
 * Why a native SDK sent no attestation, as the exact strings of
 * {@code attestation.attest_skipped} in {@link Event#getDocument()}.
 * The app reports it about itself; it is diagnostic only and never changes
 * the score.
 */
public final class AttestSkippedReason {

    private AttestSkippedReason() {}

    /**
     * The challenge offered no attestation: on iOS the SDK key has no Apple app ID for
     * App Attest, on Android the app's transport returned no challenge offer.
     */
    public static final String NOT_OFFERED = "not_offered";

    /**
     * The device cannot make an attested key: App Attest is not supported on the iOS
     * device, or Android is older than 7.0 (API 24).
     */
    public static final String UNSUPPORTED = "unsupported";

    /**
     * No usable challenge ticket: the challenge request failed, or on Android the ticket
     * it returned was malformed.
     */
    public static final String CHALLENGE_UNAVAILABLE = "challenge_unavailable";

    /**
     * The platform key store failed to create, attest or sign with the key, or on Android
     * the stored key record could not be read.
     */
    public static final String KEY_ERROR = "key_error";

    /**
     * Attestation did not finish in time: a key store call ran past its budget, or another
     * identification held the attestation step too long.
     */
    public static final String TIMEOUT = "timeout";

    /**
     * The server rejected a new key from this install (on iOS, also when it asked for a
     * replacement), and the SDK is waiting out a backoff before it registers another.
     */
    public static final String BACKOFF = "backoff";

    /**
     * The report has no usable install ID, or on iOS the stored key record could not be
     * read or saved.
     */
    public static final String INSTALL_UNAVAILABLE = "install_unavailable";
}
