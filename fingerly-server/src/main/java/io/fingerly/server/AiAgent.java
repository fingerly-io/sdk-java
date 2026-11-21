package io.fingerly.server;

/**
 * The AI agent behind an event whose {@link Event#getVisitorKind()} is
 * {@link VisitorKind#AI_BOT}.
 *
 * <p>The agent is named by a Web Bot Auth signature when the request carried
 * one, otherwise by its user agent. {@link #getVerification()} says how far
 * that was checked: {@link AiVerificationMethod#SIGNATURE} (the operator's
 * published key signed the request) or {@link AiVerificationMethod#IP_LIST}
 * (the address is inside the operator's published ranges), with result
 * {@link AiVerificationResult#PASS}, {@link AiVerificationResult#FAIL} (the
 * name was borrowed), or {@link AiVerificationResult#NOT_ATTEMPTED} with
 * method {@link AiVerificationMethod#NONE} (the operator publishes nothing to
 * check against, or it could not be checked; the name is then only the
 * client's claim).
 *
 * <p>{@link #getPurpose()}, {@link #getRuntime()} and the verification values
 * are plain strings, so a value the API adds later still decodes.
 */
public final class AiAgent {

    private String name;
    private String provider;
    private String purpose;
    private String runtime;
    private boolean declared;
    private AiVerification verification;

    AiAgent() {}

    /** The agent as its operator names it, such as {@code ChatGPT-User} or {@code Claude-User}. */
    public String getName() {
        return name;
    }

    /** The company that operates the agent, such as {@code OpenAI}, {@code Anthropic} or {@code Google}. */
    public String getProvider() {
        return provider;
    }

    /**
     * What the operator says the traffic is for: {@code training},
     * {@code search}, {@code user_fetch} or {@code agent}. See {@link AiPurpose}.
     */
    public String getPurpose() {
        return purpose;
    }

    /**
     * Where the agent runs: {@code provider_cloud}, {@code hosted_browser} or
     * {@code user_device}. See {@link AiRuntime}.
     */
    public String getRuntime() {
        return runtime;
    }

    /** True when the agent named itself, in its user agent or a signature. */
    public boolean isDeclared() {
        return declared;
    }

    /** How the agent's claimed identity was checked, and what that found. */
    public AiVerification getVerification() {
        return verification;
    }

    @Override
    public String toString() {
        return "AiAgent[name=" + name + ", provider=" + provider + ", purpose=" + purpose + "]";
    }
}
