package io.fingerly.server;

/** The {@link Event#getEnvironment()} values, as the exact strings the API sends. */
public final class Environment {

    private Environment() {}

    /** Production. */
    public static final String PRODUCTION = "production";

    /** Staging. */
    public static final String STAGING = "staging";

    /** Development. */
    public static final String DEVELOPMENT = "development";
}
