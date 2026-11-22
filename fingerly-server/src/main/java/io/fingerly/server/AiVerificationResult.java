package io.fingerly.server;

/**
 * The {@link AiVerification#getResult()} values, as the exact strings the API
 * sends. The API can add values, so compare against these rather than assume
 * the list is complete.
 */
public final class AiVerificationResult {

    private AiVerificationResult() {}

    /** The claim held. */
    public static final String PASS = "pass";

    /** The claim was checked and did not hold. */
    public static final String FAIL = "fail";

    /** No check was made. */
    public static final String NOT_ATTEMPTED = "not_attempted";
}
