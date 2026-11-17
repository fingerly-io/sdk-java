package io.fingerly.server;

/**
 * The detail behind {@link AttestationState}, as the exact strings of
 * {@code attestation.status} in {@link Event#getDocument()}.
 */
public final class AttestationStatus {

    private AttestationStatus() {}

    /** The evidence verified. */
    public static final String VERIFIED = "verified";

    /** The evidence could not be checked either way. */
    public static final String INDETERMINATE = "indeterminate";

    /** The evidence was rejected. */
    public static final String INVALID = "invalid";

    /** The report carried no evidence. */
    public static final String ABSENT = "absent";

    /** The key has nothing configured to verify the evidence against. */
    public static final String UNCONFIGURED = "unconfigured";
}
