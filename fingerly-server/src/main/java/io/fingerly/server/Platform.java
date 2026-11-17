package io.fingerly.server;

/**
 * The {@link Event#getPlatform()} scoring profiles, as the exact strings the
 * API sends. {@link Event#getSdkPlatform()} is what the SDK called itself and
 * is not one of these.
 */
public final class Platform {

    private Platform() {}

    /** Browsers. */
    public static final String WEB = "web";

    /** Android apps. */
    public static final String ANDROID = "android";

    /** iOS apps. */
    public static final String IOS = "ios";
}
