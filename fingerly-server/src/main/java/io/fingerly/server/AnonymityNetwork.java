package io.fingerly.server;

/**
 * The {@link Event#getAnonymityNetwork()} values, as the exact strings the API
 * sends. The accessor is null on an ordinary connection.
 */
public final class AnonymityNetwork {

    private AnonymityNetwork() {}

    /** A Tor exit node. */
    public static final String TOR = "tor";

    /** A privacy relay such as Apple's Private Relay. */
    public static final String RELAY = "relay";

    /** A reported VPN endpoint. */
    public static final String VPN = "vpn";

    /** A proxy. */
    public static final String PROXY = "proxy";

    /** A hosting or datacenter network. */
    public static final String HOSTING = "hosting";
}
