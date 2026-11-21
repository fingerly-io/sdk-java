package io.fingerly.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One request your server served, as raw input for
 * {@link AgentsService#reportSightings}.
 *
 * <p>Only these fields are ever sent: never the path, the query string,
 * cookies, {@code Authorization}, any other header, the body or the response.
 * The API decides what sent the request; nothing here is classified first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AgentSightingReport {

    @JsonProperty("user_agent")
    private final String userAgent;

    @JsonProperty("client_ip")
    private final String clientIp;

    private final String host;
    private final String method;
    private final String signature;

    @JsonProperty("signature_input")
    private final String signatureInput;

    @JsonProperty("signature_agent")
    private final String signatureAgent;

    private final long count;

    private AgentSightingReport(Builder builder) {
        this.userAgent = builder.userAgent == null ? "" : builder.userAgent;
        this.clientIp = builder.clientIp;
        this.host = builder.host;
        this.method = builder.method;
        this.signature = builder.signature;
        this.signatureInput = builder.signatureInput;
        this.signatureAgent = builder.signatureAgent;
        this.count = builder.count;
    }

    /** A new builder. {@link Builder#clientIp} is required. */
    public static Builder builder() {
        return new Builder();
    }

    /** The {@code User-Agent} header as received, or empty when absent. */
    public String getUserAgent() {
        return userAgent;
    }

    /** The visitor's address: one IPv4 or IPv6 literal. */
    public String getClientIp() {
        return clientIp;
    }

    /** The {@code Host} the request was sent to, or null. */
    public String getHost() {
        return host;
    }

    /** The request method, or null. */
    public String getMethod() {
        return method;
    }

    /** The {@code Signature} header, or null. */
    public String getSignature() {
        return signature;
    }

    /** The {@code Signature-Input} header, or null. */
    public String getSignatureInput() {
        return signatureInput;
    }

    /** The {@code Signature-Agent} header, or null. */
    public String getSignatureAgent() {
        return signatureAgent;
    }

    /** How many times this exact request was served since it was last reported. */
    public long getCount() {
        return count;
    }

    /** The count only: the fields describe a visitor and stay out of logs. */
    @Override
    public String toString() {
        return "AgentSightingReport[count=" + count + "]";
    }

    /** Builds an {@link AgentSightingReport}. */
    public static final class Builder {
        private String userAgent;
        private String clientIp;
        private String host;
        private String method;
        private String signature;
        private String signatureInput;
        private String signatureAgent;
        private long count = 1;

        private Builder() {}

        /** The {@code User-Agent} header as received; null is sent as empty. At most 4096 bytes. */
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /**
         * The visitor's address from infrastructure you trust: one IPv4 or
         * IPv6 literal, never a forwarded list. Required.
         */
        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        /** The {@code Host} the request was sent to, which a signing agent signs. */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /** The request method; the API reads an absent one as {@code GET}. */
        public Builder method(String method) {
            this.method = method;
            return this;
        }

        /** The {@code Signature} header, when present. At most 4096 bytes. */
        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        /** The {@code Signature-Input} header, when present. At most 4096 bytes. */
        public Builder signatureInput(String signatureInput) {
            this.signatureInput = signatureInput;
            return this;
        }

        /** The {@code Signature-Agent} header, when present. At most 4096 bytes. */
        public Builder signatureAgent(String signatureAgent) {
            this.signatureAgent = signatureAgent;
            return this;
        }

        /** How many times this exact request was served, from 1 to 1,000,000. Defaults to 1. */
        public Builder count(long count) {
            this.count = count;
            return this;
        }

        /**
         * Builds the report.
         *
         * @throws IllegalArgumentException if clientIp is missing or count is
         *     not between 1 and 1,000,000
         */
        public AgentSightingReport build() {
            if (clientIp == null || clientIp.isEmpty()) {
                throw new IllegalArgumentException("clientIp is required");
            }
            if (count < 1 || count > AgentsService.MAX_COUNT) {
                throw new IllegalArgumentException("count must be between 1 and 1000000");
            }
            return new AgentSightingReport(this);
        }
    }
}
