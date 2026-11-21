package io.fingerly.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

/**
 * What the API said sent one request your server served: the answer to one
 * reported request, and what {@code AgentSightings.lookup} returns.
 *
 * <p>Compare {@link #getVisitorKind()} against the {@link VisitorKind}
 * constants. A lookup the SDK cannot answer from memory returns
 * {@link #UNKNOWN}, whose visitor kind matches none of them, so a check such
 * as {@code VisitorKind.AI_BOT.equals(answer.getVisitorKind())} fails open:
 * serve the request as usual.
 *
 * <p>The visitor kind and the {@link AiAgent} values are plain strings, so a
 * value the API adds later still decodes.
 */
public final class AgentSighting {

    /**
     * The answer when nothing is known about a request yet: not reported, not
     * answered, expired, or not reportable. Treat it as "serve as usual".
     * Its visitor kind is {@code "unknown"}, its ttl 0, and it has neither an
     * agent nor an AI object.
     */
    public static final AgentSighting UNKNOWN = new AgentSighting(0, "unknown");

    private long ttl;

    @JsonProperty("visitor_kind")
    private String visitorKind;

    private String agent;

    private AiAgent ai;

    AgentSighting() {}

    private AgentSighting(long ttl, String visitorKind) {
        this.ttl = ttl;
        this.visitorKind = visitorKind;
    }

    /**
     * The seconds the API allows this answer to be reused for the exact same
     * user agent, address and {@code Signature-Agent}; 0 for a signed request,
     * which is checked every time.
     */
    public long getTtl() {
        return ttl;
    }

    /**
     * {@code human}, {@code ai_bot}, {@code automation} or
     * {@code search_crawler}: see {@link VisitorKind}. {@code "unknown"} only
     * on {@link #UNKNOWN}.
     */
    public String getVisitorKind() {
        return visitorKind;
    }

    /**
     * The agent's name where the API knows it, such as {@code GPTBot} or
     * {@code Googlebot}, or null for a person's browser.
     */
    public String getAgent() {
        return agent;
    }

    /**
     * Present when {@link #getVisitorKind()} is {@link VisitorKind#AI_BOT}:
     * who operates the agent, what its traffic is for, and how far its claim
     * was checked.
     */
    public Optional<AiAgent> getAi() {
        return Optional.ofNullable(ai);
    }

    /** True for {@link #UNKNOWN}, the answer that means "serve as usual". */
    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    @Override
    public String toString() {
        return "AgentSighting[visitorKind=" + visitorKind + ", agent=" + agent + ", ttl=" + ttl + "]";
    }
}
