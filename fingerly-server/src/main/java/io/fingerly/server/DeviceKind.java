package io.fingerly.server;

/** The {@link Event#getDeviceKind()} values, as the exact strings the API sends. */
public final class DeviceKind {

    private DeviceKind() {}

    /** A desktop or laptop. */
    public static final String DESKTOP = "desktop";

    /** A phone. */
    public static final String MOBILE = "mobile";

    /** A tablet. */
    public static final String TABLET = "tablet";

    /** A client with no device at all. */
    public static final String SERVER = "server";
}
