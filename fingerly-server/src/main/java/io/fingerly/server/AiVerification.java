package io.fingerly.server;

/** How an {@link AiAgent}'s claimed identity was checked, and what that found. */
public final class AiVerification {

    private String method;
    private String result;

    AiVerification() {}

    /**
     * {@code signature}, {@code ip_list}, {@code reverse_dns} or {@code none}.
     * See {@link AiVerificationMethod}.
     */
    public String getMethod() {
        return method;
    }

    /** {@code pass}, {@code fail} or {@code not_attempted}. See {@link AiVerificationResult}. */
    public String getResult() {
        return result;
    }

    @Override
    public String toString() {
        return "AiVerification[method=" + method + ", result=" + result + "]";
    }
}
