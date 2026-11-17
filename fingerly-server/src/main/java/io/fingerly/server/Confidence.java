package io.fingerly.server;

/** The {@link EventTrigger#getConfidence()} values, as the exact strings the API sends. */
public final class Confidence {

    private Confidence() {}

    /** Low confidence. */
    public static final String LOW = "low";

    /** Medium confidence. */
    public static final String MEDIUM = "medium";

    /** High confidence. */
    public static final String HIGH = "high";
}
