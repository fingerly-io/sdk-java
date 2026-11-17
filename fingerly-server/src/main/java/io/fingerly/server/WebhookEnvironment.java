package io.fingerly.server;

/** The {@link WebhookEvent#getEnvironment()} values, as the exact strings the API sends. */
public final class WebhookEnvironment {

    private WebhookEnvironment() {}

    /** Sent for production traffic. */
    public static final String LIVE = "live";

    /** Sent for staging and development traffic. */
    public static final String TEST = "test";
}
