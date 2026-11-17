package io.fingerly.server;

/**
 * The {@link Event#getState()} values, as the exact strings the API sends.
 *
 * <p>A stored event is {@link #ENRICHED} or {@link #UNAVAILABLE}. An event
 * still being recorded is not returned as a row: the read throws a
 * {@link FingerlyApiException} whose {@code isPending()} is true.
 */
public final class EventState {

    private EventState() {}

    /** Accepted and not yet recorded. */
    public static final String PENDING = "pending";

    /** The submission was enriched and stored. */
    public static final String ENRICHED = "enriched";

    /** Enrichment produced nothing; {@link Event#getReason()} says why. */
    public static final String UNAVAILABLE = "unavailable";
}
