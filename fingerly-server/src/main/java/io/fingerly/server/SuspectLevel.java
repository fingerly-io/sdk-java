package io.fingerly.server;

/**
 * The {@link Event#getSuspectLevel()} bands, as the exact strings the API sends.
 *
 * <p>The accessor stays a plain string, null when the event was not scored.
 *
 * <pre>{@code
 * if (SuspectLevel.HIGH.equals(event.getSuspectLevel())) {
 *     return "review";
 * }
 * }</pre>
 */
public final class SuspectLevel {

    private SuspectLevel() {}

    /** The lowest band. */
    public static final String LOW = "low";

    /** The middle band. */
    public static final String MEDIUM = "medium";

    /** The highest band. */
    public static final String HIGH = "high";
}
