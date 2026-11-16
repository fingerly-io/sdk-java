package io.fingerly.server;

import java.util.Set;

/** One signal group that contributed to an event's suspect score. */
public final class EventTrigger {

    /** Hardware attestation failed cryptographic, chain, replay or ownership verification. */
    public static final String SIGNAL_ATTESTATION_FAILED = "attestation_failed";

    /** Native device could have offered hardware attestation proof and did not. */
    public static final String SIGNAL_ATTESTATION_MISSING = "attestation_missing";

    /** Reported hardware, OS or runtime realms contradict each other. */
    public static final String SIGNAL_DEVICE_INCONSISTENT = "device_inconsistent";

    /** Required security collectors were omitted or selectively timed out. */
    public static final String SIGNAL_REPORT_INCOMPLETE = "report_incomplete";

    /** Runtime code or natives were patched or tampered with. */
    public static final String SIGNAL_TAMPERING = "tampering";

    /** Browser tampering signal group key on stored event rows. */
    public static final String SIGNAL_BROWSER_TAMPERING = "browser_tampering";

    /** TLS chain anchored at a non-WebPKI or user-installed root authority. */
    public static final String SIGNAL_CUSTOM_ROOT_CA = "custom_root_ca";

    /** Managed Android work profile or secondary OS user profile without cloner indicators. */
    public static final String SIGNAL_SECONDARY_USER_PROFILE = "secondary_user_profile";

    /** The v3 anti-spoofing signal identifiers. */
    public static final Set<String> V3_SIGNALS = Set.of(
            SIGNAL_ATTESTATION_FAILED,
            SIGNAL_DEVICE_INCONSISTENT,
            SIGNAL_REPORT_INCOMPLETE,
            SIGNAL_TAMPERING,
            SIGNAL_CUSTOM_ROOT_CA,
            SIGNAL_SECONDARY_USER_PROFILE);

    private String signal;
    private String group;
    private int weight;
    private String confidence;

    EventTrigger() {}

    /** The signal or signal group, such as {@code vpn}, {@code tampering} or {@code attestation_failed}. */
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

    /** {@code low}, {@code medium} or {@code high}. */
    public String getConfidence() {
        return confidence;
    }

    @Override
    public String toString() {
        return "EventTrigger[" + signal + "=" + weight + "]";
    }
}
