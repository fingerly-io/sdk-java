package io.fingerly.server;

/**
 * The {@link AiVerification#getMethod()} values, as the exact strings the API
 * sends. The API can add values, so compare against these rather than assume
 * the list is complete.
 */
public final class AiVerificationMethod {

    private AiVerificationMethod() {}

    /** A signed request. */
    public static final String SIGNATURE = "signature";

    /** The operator's published address ranges. */
    public static final String IP_LIST = "ip_list";

    /** A reverse lookup confirmed forward. */
    public static final String REVERSE_DNS = "reverse_dns";

    /** No check was made. */
    public static final String NONE = "none";
}
