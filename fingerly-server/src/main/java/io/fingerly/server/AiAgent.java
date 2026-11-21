package io.fingerly.server;

/**
 * The AI agent behind an event whose {@link Event#getVisitorKind()} is
 * {@link VisitorKind#AI_BOT}.
 *
 * <p>The identity comes from the agent's user agent, which anyone can copy.
 * It is not verified yet: {@link #getVerification()} says how far the claim
 * was checked. Read it as a label, not as proof.
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
