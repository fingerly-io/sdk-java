package io.fingerly.server;

/**
 * The outcome of {@link EventsService#verify(String, VerifyOptions)}.
 *
 * <p>{@link #getReason()} is one of the {@code REASON_} constants, or
 * {@code ""} when {@link #isOk()}. It never reflects the suspect score: score
 * policy stays with the caller.
 */
public final class VerifyResult {

    /** No event has this request id: never issued, or past retention. */
    public static final String REASON_NOT_FOUND = "not_found";

    /** The event is older than the age limit, or its time is unreadable. */
    public static final String REASON_TOO_OLD = "too_old";

    /** The event's tag is not the expected one. */
    public static final String REASON_TAG_MISMATCH = "tag_mismatch";

    /** The submission was unidentifiable: its {@code visitor_id} is empty. */
    public static final String REASON_UNIDENTIFIABLE = "unidentifiable";

    /** The event was not scored. */
    public static final String REASON_UNSCORED = "unscored";

    /** Another call consumed the event first. */
    public static final String REASON_ALREADY_CONSUMED = "already_consumed";

    private final String reason;
    private final Event event;

    private VerifyResult(String reason, Event event) {
        this.reason = reason;
        this.event = event;
    }

    static VerifyResult ok(Event event) {
        return new VerifyResult("", event);
    }

    static VerifyResult failed(String reason, Event event) {
        return new VerifyResult(reason, event);
    }

    /** True when every check passed and, unless turned off, the event was consumed. */
    public boolean isOk() {
        return reason.isEmpty();
    }

    /** The first check that failed, as a {@code REASON_} value, or {@code ""} when ok. */
    public String getReason() {
        return reason;
    }

    /** The event read, or null for {@link #REASON_NOT_FOUND}. */
    public Event getEvent() {
        return event;
    }

    @Override
    public String toString() {
        return "VerifyResult[ok=" + isOk() + ", reason=" + reason + ", event=" + event + "]";
    }
}
