package io.fingerly.server;

import java.util.Set;

/** One signal group that contributed to an event's suspect score. */
public final class EventTrigger {

    /** Hardware attestation failed cryptographic, chain, replay or ownership verification. */
    public static final String SIGNAL_ATTESTATION_FAILED = Signal.ATTESTATION_FAILED;

    /** Native device could have offered hardware attestation proof and did not. */
    public static final String SIGNAL_ATTESTATION_MISSING = Signal.ATTESTATION_MISSING;

    /** Unproven or rejected native report naming an install an attested key owns. */
    public static final String SIGNAL_ATTESTATION_DOWNGRADE = Signal.ATTESTATION_DOWNGRADE;

    /** Reported hardware, OS or runtime realms contradict each other. */
    public static final String SIGNAL_DEVICE_INCONSISTENT = Signal.DEVICE_INCONSISTENT;

    /** Evidence a report always carries is missing: a required native security source or the web worker-realm cross-check. */
    public static final String SIGNAL_REPORT_INCOMPLETE = Signal.REPORT_INCOMPLETE;

    /** Native security source ran but lists probes that threw, were denied or timed out. */
    public static final String SIGNAL_PROBE_ERRORS = Signal.PROBE_ERRORS;

    /** Browser report without {@code Sec-Fetch-*} headers from a browser that always sends them. */
    public static final String SIGNAL_FETCH_METADATA_MISSING = Signal.FETCH_METADATA_MISSING;

    /** Browser report that carried no single-use freshness ticket. */
    public static final String SIGNAL_WEB_TICKET_MISSING = Signal.WEB_TICKET_MISSING;

    /** Browser report whose freshness ticket did not verify: bad signature, another key's or origin's, expired, or already used. */
    public static final String SIGNAL_WEB_TICKET_INVALID = Signal.WEB_TICKET_INVALID;

    /** Runtime code or natives were patched or tampered with. */
    public static final String SIGNAL_TAMPERING = Signal.TAMPERING;

    /** Browser tampering signal group key on stored event rows. */
    public static final String SIGNAL_BROWSER_TAMPERING = Signal.BROWSER_TAMPERING;

    /** TLS chain anchored at a non-WebPKI or user-installed root authority. */
    public static final String SIGNAL_CUSTOM_ROOT_CA = Signal.CUSTOM_ROOT_CA;

    /** Managed Android work profile or secondary OS user profile without cloner indicators. */
    public static final String SIGNAL_SECONDARY_USER_PROFILE = Signal.SECONDARY_USER_PROFILE;

    /** The v3 anti-spoofing signal identifiers. */
    public static final Set<String> V3_SIGNALS = Set.of(
            SIGNAL_ATTESTATION_FAILED,
            SIGNAL_ATTESTATION_DOWNGRADE,
            SIGNAL_DEVICE_INCONSISTENT,
            SIGNAL_REPORT_INCOMPLETE,
            SIGNAL_PROBE_ERRORS,
            SIGNAL_FETCH_METADATA_MISSING,
            SIGNAL_WEB_TICKET_MISSING,
            SIGNAL_WEB_TICKET_INVALID,
            SIGNAL_TAMPERING,
            SIGNAL_CUSTOM_ROOT_CA,
            SIGNAL_SECONDARY_USER_PROFILE);

    private String signal;
    private String group;
    private int weight;
    private String confidence;

    EventTrigger() {}

    /** The signal or signal group, such as {@code vpn}, {@code tampering} or {@code attestation_failed}. See {@link Signal}. */
    public String getSignal() {
        return signal;
    }

    /** Optional group identifier when returned alongside a raw signal. */
    public String getGroup() {
        return group;
    }

    /** The group's combined weight in this score. */
    public int getWeight() {
        return weight;
    }

    /** {@code low}, {@code medium} or {@code high}. See {@link Confidence}. */
    public String getConfidence() {
        return confidence;
    }

    @Override
    public String toString() {
        return "EventTrigger[" + signal + "=" + weight + "]";
    }
}
