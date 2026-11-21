package io.fingerly.server;

/**
 * The {@link AiAgent#getRuntime()} values, as the exact strings the API sends.
 * The API can add values, so compare against these rather than assume the
 * list is complete.
 */
public final class AiRuntime {

    private AiRuntime() {}

    /** The operator's own infrastructure. */
    public static final String PROVIDER_CLOUD = "provider_cloud";

    /** A third party's cloud browser. */
    public static final String HOSTED_BROWSER = "hosted_browser";

    /** A person's own browser. */
    public static final String USER_DEVICE = "user_device";
}
