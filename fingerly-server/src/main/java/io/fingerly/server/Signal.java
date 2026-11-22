package io.fingerly.server;

/**
 * The signal identifiers Fingerly reports, as the exact strings the API sends.
 *
 * <p>Two kinds of identifier share this vocabulary. {@link EventTrigger#getSignal()}
 * on a stored event carries a signal group, such as {@link #VPN}, so one VPN is
 * one trigger however many detectors saw it. The archived document's
 * {@code risk.triggers[].signal} carries the detector itself, such as
 * {@link #VPN_PUBLIC}. Many groups have a single detector of the same name,
 * such as {@link #TOR} or {@link #FINGERPRINT_SUPPRESSED}.
 *
 * <p>The accessors stay plain strings, so a signal the server adds after this
 * SDK was released reads normally: compare with {@code equals} or
 * {@code switch}, and keep a default branch for values not listed here.
 *
 * <pre>{@code
 * boolean suppressed = event.getTriggers().stream()
 *         .anyMatch(t -> Signal.FINGERPRINT_SUPPRESSED.equals(t.getSignal()));
 * }</pre>
 */
public final class Signal {

    private Signal() {}

    /** Group and detector: a published Tor exit node. */
    public static final String TOR = "tor";

    /** Group: a declared datacenter proxy or an unexplained datacenter address. */
    public static final String DATACENTER_PROXY = "datacenter_proxy";

    /** Detector: a datacenter proxy, an address that looks like a proxy and lives where servers do. */
    public static final String PROXY = "proxy";

    /** Detector: an address that lives where servers do, with no proxy or VPN claim attached. */
    public static final String DATACENTER_IP = "datacenter_ip";

    /** Group and detector: a proxy on a consumer connection. */
    public static final String RESIDENTIAL_PROXY = "residential_proxy";

    /** Detector: a residential proxy at low confidence, in confidence weighting mode. */
    public static final String RESIDENTIAL_PROXY_LOW_CONFIDENCE = "residential_proxy_low_confidence";

    /** Detector: a residential proxy at medium confidence, in confidence weighting mode. */
    public static final String RESIDENTIAL_PROXY_MEDIUM_CONFIDENCE = "residential_proxy_medium_confidence";

    /** Detector: a residential proxy at high confidence, in confidence weighting mode. */
    public static final String RESIDENTIAL_PROXY_HIGH_CONFIDENCE = "residential_proxy_high_confidence";

    /** Group: the traffic arrived over a VPN. */
    public static final String VPN = "vpn";

    /** Detector: a commercial VPN endpoint. */
    public static final String VPN_PUBLIC = "vpn_public";

    /** Detector: Apple's Private Relay and similar relays. */
    public static final String VPN_RELAY = "vpn_relay";

    /** Detector: a VPN on a mobile carrier network. Native only. */
    public static final String VPN_MOBILE = "vpn_mobile";

    /** Detector: a claimed operating system the connection's own fingerprint disagrees with. Web only. */
    public static final String VPN_OS_MISMATCH = "vpn_os_mismatch";

    /** Detector: a VPN at low confidence, in confidence weighting mode. */
    public static final String VPN_LOW_CONFIDENCE = "vpn_low_confidence";

    /** Detector: a VPN at medium confidence, in confidence weighting mode. */
    public static final String VPN_MEDIUM_CONFIDENCE = "vpn_medium_confidence";

    /** Detector: a VPN at high confidence, in confidence weighting mode. */
    public static final String VPN_HIGH_CONFIDENCE = "vpn_high_confidence";

    /** Group: the address is on a public spam or attack-source blocklist. */
    public static final String IP_BLOCKLIST = "ip_blocklist";

    /** Detector: an address on a mail-abuse blocklist. */
    public static final String IP_BLOCKLIST_EMAIL_SPAM = "ip_blocklist_email_spam";

    /** Detector: an address on an attack-source blocklist. */
    public static final String IP_BLOCKLIST_ATTACK_SOURCE = "ip_blocklist_attack_source";

    /** Group: the address has a recent abuse history. */
    public static final String IP_REPUTATION = "ip_reputation";

    /** Detector: an address with a history of abuse, broader and weaker than either blocklist. */
    public static final String IP_ABUSER = "ip_abuser";

    /** Group: a bogon or anycast address. Both detectors ship disabled. */
    public static final String NETWORK_ANOMALY = "network_anomaly";

    /** Detector: an address that cannot appear on the public internet. */
    public static final String BOGON_IP = "bogon_ip";

    /** Detector: an address announced from many places at once. */
    public static final String ANYCAST_IP = "anycast_ip";

    /** Group: the device's own idea of where it is disagrees with its address. */
    public static final String LOCATION_SPOOFING = "location_spoofing";

    /** Detector: the device's timezone or locale disagrees with where its address is. */
    public static final String GEO_MISMATCH = "geo_mismatch";

    /** Group: the app runs in the iOS simulator. */
    public static final String IOS_SIMULATOR = "ios_simulator";

    /** Detector: the iOS simulator. */
    public static final String SIMULATOR = "simulator";

    /** Group: the app runs in an Android emulator. */
    public static final String ANDROID_EMULATOR = "android_emulator";

    /** Detector: an Android emulator. */
    public static final String EMULATOR = "emulator";

    /** Group and detector: a browser running inside a virtual machine. */
    public static final String VIRTUAL_MACHINE = "virtual_machine";

    /** Group: a jailbroken iOS device. */
    public static final String JAILBROKEN_DEVICE = "jailbroken_device";

    /** Detector: a jailbroken iOS device. */
    public static final String JAILBREAK = "jailbreak";

    /** Group: a rooted Android device. */
    public static final String ROOTED_DEVICE = "rooted_device";

    /** Detector: a rooted Android device. */
    public static final String ROOT_APPS = "root_apps";

    /** Detector: Play Integrity says the device meets no device, strong or virtual integrity. */
    public static final String PLAY_DEVICE_INTEGRITY_FAILED = "play_device_integrity_failed";

    /** Group: the app runs under a cloning framework or is not recognised by Google Play. */
    public static final String CLONED_APP = "cloned_app";

    /** Detector: an app running under a cloning framework. */
    public static final String APP_CLONER = "app_cloner";

    /** Detector: an app Google Play does not recognise, or signed by a certificate the key does not allow. */
    public static final String PLAY_APP_UNRECOGNIZED = "play_app_unrecognized";

    /** Group and detector: a device that looks mass-provisioned or reset-fresh. */
    public static final String DEVICE_FARM = "device_farm";

    /** Detector: an iOS device whose App Attest risk metric shows the app reinstalled or the device reset again and again. */
    public static final String REPEATED_REINSTALL = "repeated_reinstall";

    /** Detector: Play Integrity recent device activity at {@code LEVEL_3}. */
    public static final String DEVICE_ACTIVITY_HIGH = "device_activity_high";

    /** Detector: Play Integrity recent device activity at {@code LEVEL_4}. */
    public static final String DEVICE_ACTIVITY_VERY_HIGH = "device_activity_very_high";

    /** Group and detector: a proxy intercepting the app's own TLS. */
    public static final String MITM_ATTACK = "mitm_attack";

    /** Group: an instrumentation framework is attached to the process. */
    public static final String FRIDA_DETECTED = "frida_detected";

    /** Detector: an instrumentation framework attached to the process. */
    public static final String FRIDA = "frida";

    /** Group: the runtime was edited, claims an identity it does not have, or reused a freshness ticket. */
    public static final String BROWSER_TAMPERING = "browser_tampering";

    /** Detector: runtime code or natives were patched or contradict what was observed. */
    public static final String TAMPERING = "tampering";

    /** Detector: a build made to defeat fingerprinting, whose observed identity contradicts its claim. */
    public static final String ANTIDETECT_BROWSER = "antidetect_browser";

    /** Detector: a browser report whose freshness ticket did not verify: bad signature, another key's or origin's, expired, or already used. */
    public static final String WEB_TICKET_INVALID = "web_ticket_invalid";

    /** Group and detector: a report too thin to identify anyone. */
    public static final String FINGERPRINT_SUPPRESSED = "fingerprint_suppressed";

    /** Group and detector: a browser in a non-default, opt-in anti-fingerprinting mode that replaces audio, graphics, text metrics and locale readings with synthetic noise or empty answers. */
    public static final String IDENTITY_OBFUSCATED = "identity_obfuscated";

    /** Group and detector: an open inspector or an attached debugger. */
    public static final String DEVELOPER_TOOLS = "developer_tools";

    /** Group and detector: a session driven through remote desktop software. */
    public static final String REMOTE_CONTROL = "remote_control";

    /** Group: the client is not a person. */
    public static final String BOT = "bot";

    /** Detector: a browser driven by something other than a person. */
    public static final String AUTOMATION = "automation";

    /** Detector: a client that says it is not a browser, or a known bad crawler. */
    public static final String BAD_BOT = "bad_bot";

    /** Detector: a client that names a known AI agent and arrives from outside the addresses that agent's operator publishes. */
    public static final String AI_SPOOFED = "ai_spoofed";

    /** Detector: a browser report without {@code Sec-Fetch-*} headers from a browser that always sends them. */
    public static final String FETCH_METADATA_MISSING = "fetch_metadata_missing";

    /** Group and detector: a device seen far more often than an ordinary one. */
    public static final String HIGH_ACTIVITY = "high_activity";

    /** Group: a private browsing window. */
    public static final String INCOGNITO_MODE = "incognito_mode";

    /** Detector: a private browsing window. */
    public static final String INCOGNITO = "incognito";

    /** Group and detector: hardened privacy settings. Ships at weight zero. */
    public static final String PRIVACY_SETTINGS = "privacy_settings";

    /** Group and detector: a page running inside an app's web view. Ships at weight zero. */
    public static final String EMBEDDED_WEB_VIEW = "embedded_web_view";

    /** Group and detector: a device on a voice call while the submission was made. */
    public static final String ACTIVE_CALL = "active_call";

    /** Group and detector: a native device that could have offered hardware attestation proof and did not. */
    public static final String ATTESTATION_MISSING = "attestation_missing";

    /** Group and detector: hardware attestation failed cryptographic, chain, replay or ownership verification. */
    public static final String ATTESTATION_FAILED = "attestation_failed";

    /** Detector: an unproven or rejected native report naming an install an attested key owns. */
    public static final String ATTESTATION_DOWNGRADE = "attestation_downgrade";

    /** Group and detector: reported hardware, OS or runtime realms contradict each other. */
    public static final String DEVICE_INCONSISTENT = "device_inconsistent";

    /** Group and detector: evidence a report always carries is missing. */
    public static final String REPORT_INCOMPLETE = "report_incomplete";

    /** Detector: a native security source ran but lists probes that threw, were denied or timed out. */
    public static final String PROBE_ERRORS = "probe_errors";

    /** Detector: a browser report that carried no single-use freshness ticket. */
    public static final String WEB_TICKET_MISSING = "web_ticket_missing";

    /** Group and detector: a TLS chain anchored at a non-system or user-installed root authority. */
    public static final String CUSTOM_ROOT_CA = "custom_root_ca";

    /** Group and detector: a managed Android work profile or secondary user profile without cloner indicators. */
    public static final String SECONDARY_USER_PROFILE = "secondary_user_profile";
}
