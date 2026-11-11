package io.fingerly.server;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A stored identification event.
 *
 * <p>{@link #getSuspectScore()} returns an empty {@link Optional} when the
 * request was not scored, which must not be read as zero: a visitor about whom
 * nothing fired scores 0, while a submission that was never enriched has no
 * score at all.
 */
public final class Event {

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("visitor_id")
    private String visitorId;

    @JsonProperty("occurred_at")
    private Instant occurredAt;

    private String state;
    private String environment;
    private String platform;

    @JsonProperty("sdk_platform")
    private String sdkPlatform;

    @JsonProperty("sdk_version")
    private String sdkVersion;

    private String origin;

    @JsonProperty("url_host")
    private String urlHost;

    @JsonProperty("url_path")
    private String urlPath;

    private String tag;

    @JsonProperty("ip_network")
    private String ipNetwork;

    @JsonProperty("country_code")
    private String countryCode;

    @JsonProperty("country_name")
    private String countryName;

    private String asn;

    @JsonProperty("asn_name")
    private String asnName;

    @JsonProperty("anonymity_network")
    private String anonymityNetwork;

    private String agent;

    @JsonProperty("agent_version")
    private String agentVersion;

    private String os;

    @JsonProperty("device_kind")
    private String deviceKind;

    @JsonProperty("visitor_kind")
    private String visitorKind;

    /** Boxed on purpose: null means unscored, which is not the same as 0. */
    @JsonProperty("suspect_score")
    private Integer suspectScore;

    @JsonProperty("suspect_level")
    private String suspectLevel;

    private List<EventTrigger> triggers;

    @JsonProperty("has_document")
    private boolean hasDocument;

    private JsonNode document;

    @JsonIgnore
    private transient JsonNode raw;
    private transient String rawJson;

    Event() {}

    void setRaw(JsonNode raw, String rawJson) {
        this.raw = raw;
        this.rawJson = rawJson;
    }

    /** The identification's id, a UUIDv7. */
    public String getRequestId() {
        return requestId;
    }

    /** The visitor the device was identified as. */
    public String getVisitorId() {
        return visitorId;
    }

    /** When the request arrived. */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** {@code enriched}, or {@code unavailable} when the lookup could not run. */
    public String getState() {
        return state;
    }

    /** {@code production}, {@code staging} or {@code development}. */
    public String getEnvironment() {
        return environment;
    }

    /** The scoring profile applied: {@code web}, {@code android} or {@code ios}. */
    public String getPlatform() {
        return platform;
    }

    /** The SDK that sent the report, such as {@code web} or {@code flutter-android}. */
    public String getSdkPlatform() {
        return sdkPlatform;
    }

    /** That SDK's version. */
    public String getSdkVersion() {
        return sdkVersion;
    }

    /** The site the request came from. Null for native apps. */
    public String getOrigin() {
        return origin;
    }

    /** The host of the page that identified, when one was reported. */
    public String getUrlHost() {
        return urlHost;
    }

    /** The path of that page, when one was reported. */
    public String getUrlPath() {
        return urlPath;
    }

    /** Your tag, as sent. Null when none was sent. */
    public String getTag() {
        return tag;
    }

    /**
     * The network the request came from: an IPv4 /24 or an IPv6 /40, such as
     * {@code 203.0.113.0/24}. The API never returns the address itself. Null
     * when absent.
     */
    public String getIpNetwork() {
        return ipNetwork;
    }

    /** ISO 3166-1 alpha-2 country of the address. */
    public String getCountryCode() {
        return countryCode;
    }

    /** That country's name. */
    public String getCountryName() {
        return countryName;
    }

    /** The network's autonomous system number. */
    public String getAsn() {
        return asn;
    }

    /** The network's name. */
    public String getAsnName() {
        return asnName;
    }

    /**
     * {@code tor}, {@code vpn}, {@code proxy} or {@code hosting}. Null on an
     * ordinary connection, which is most of them.
     */
    public String getAnonymityNetwork() {
        return anonymityNetwork;
    }

    /** The browser or app. */
    public String getAgent() {
        return agent;
    }

    /** Its version. */
    public String getAgentVersion() {
        return agentVersion;
    }

    /** The operating system. */
    public String getOs() {
        return os;
    }

    /** {@code desktop}, {@code mobile}, {@code tablet} or {@code server}. */
    public String getDeviceKind() {
        return deviceKind;
    }

    /** {@code human}, {@code ai_bot}, {@code automation} or {@code search_crawler}. */
    public String getVisitorKind() {
        return visitorKind;
    }

    /**
     * The suspect score, empty when the request was not scored.
     *
     * <p>Never substitute zero for an empty result. An empty score means the
     * request was never enriched, not that nothing was found.
     */
    public Optional<Integer> getSuspectScore() {
        return Optional.ofNullable(suspectScore);
    }

    /** Reports whether the API assigned this event a suspect score. */
    public boolean isScored() {
        return suspectScore != null;
    }

    /** {@code low}, {@code medium} or {@code high}. Null when not scored. */
    public String getSuspectLevel() {
        return suspectLevel;
    }

    /** The signal groups that fired, heaviest first. Never null; possibly empty. */
    public List<EventTrigger> getTriggers() {
        return triggers == null ? Collections.emptyList() : Collections.unmodifiableList(triggers);
    }

    /** Whether the full submission was archived. */
    public boolean hasDocument() {
        return hasDocument;
    }

    /**
     * An allow-listed view of the archived submission, when
     * {@link #hasDocument()} is true and the archive could be read: schema,
     * request id, time, SDK platform and version, IP network, IP
     * classification (network, city-level location, detections), risk,
     * attestation and velocity. It carries no raw signals, user agent or
     * address.
     *
     * <p>Its contents are deliberately opaque, not a public contract.
     */
    public JsonNode getDocument() {
        return document;
    }

    /**
     * The response body exactly as it arrived, so a field the API adds after
     * this SDK was released is reachable without an upgrade.
     */
    public JsonNode getRaw() {
        return raw;
    }

    /**
     * The response body as the JSON text it arrived as.
     *
     * <p>{@link #getRaw()} is a Jackson 2 {@code JsonNode}. Spring Boot 4
     * serializes controller results with Jackson 3, which does not know that
     * type and writes its accessors instead of the JSON, so return or embed
     * this string rather than the node.
     */
    public String getRawJson() {
        return rawJson;
    }

    @Override
    public String toString() {
        return "Event[requestId=" + requestId + ", visitorId=" + visitorId
                + ", suspectLevel=" + suspectLevel + "]";
    }
}
