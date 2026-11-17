package io.fingerly.server;

/**
 * The {@link VerifyResult#getReason()} values, as the exact strings every
 * Fingerly SDK reports.
 *
 * <pre>{@code
 * switch (result.getReason()) {
 *     case VerifyReason.OK:
 *         break;
 *     case VerifyReason.ALREADY_CONSUMED:
 *         return "replayed";
 *     default:
 *         return "refuse";
 * }
 * }</pre>
 */
public final class VerifyReason {

    private VerifyReason() {}

    /** Every check passed. */
    public static final String OK = "";

    /** No event has this request id: never issued, or past retention. */
    public static final String NOT_FOUND = "not_found";

    /** The event is older than the age limit, or its time is unreadable. */
    public static final String TOO_OLD = "too_old";

    /** The event's tag is not the expected one. */
    public static final String TAG_MISMATCH = "tag_mismatch";

    /** The submission was unidentifiable: its {@code visitor_id} is empty. */
    public static final String UNIDENTIFIABLE = "unidentifiable";

    /** The event was not scored. */
    public static final String UNSCORED = "unscored";

    /** Another call consumed the event first. */
    public static final String ALREADY_CONSUMED = "already_consumed";
}
