package io.fingerly.server;

/**
 * The checks {@link EventsService#verify(String, VerifyOptions)} applies.
 *
 * <pre>{@code
 * VerifyOptions options = VerifyOptions.builder()
 *     .maxAgeSeconds(120)
 *     .tag("checkout")
 *     .build();
 * }</pre>
 */
public final class VerifyOptions {

    /** The age limit when none is set: five minutes. */
    public static final long DEFAULT_MAX_AGE_SECONDS = 300;

    private static final VerifyOptions DEFAULTS = builder().build();

    private final long maxAgeSeconds;
    private final String tag;
    private final boolean consume;

    private VerifyOptions(Builder builder) {
        this.maxAgeSeconds = builder.maxAgeSeconds;
        this.tag = builder.tag;
        this.consume = builder.consume;
    }

    /** A new builder, starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** The defaults: 300 seconds, no tag check, consume. */
    public static VerifyOptions defaults() {
        return DEFAULTS;
    }

    /** The oldest event, in seconds since {@code occurred_at}, that passes. */
    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    /** The tag the event must carry exactly, or null for no tag check. */
    public String getTag() {
        return tag;
    }

    /** Whether a passing event is consumed. */
    public boolean isConsume() {
        return consume;
    }

    @Override
    public String toString() {
        return "VerifyOptions[maxAgeSeconds=" + maxAgeSeconds + ", tag=" + tag
                + ", consume=" + consume + "]";
    }

    /** Builds {@link VerifyOptions}. */
    public static final class Builder {
        private long maxAgeSeconds = DEFAULT_MAX_AGE_SECONDS;
        private String tag;
        private boolean consume = true;

        private Builder() {}

        /** The oldest event that passes, in whole seconds. Defaults to 300. */
        public Builder maxAgeSeconds(long maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
            return this;
        }

        /**
         * The tag the event must carry exactly. Null, the default, skips the
         * tag check.
         */
        public Builder tag(String tag) {
            this.tag = tag;
            return this;
        }

        /**
         * Whether to consume an event that passes every other check, so it
         * backs one decision only. Defaults to true.
         */
        public Builder consume(boolean consume) {
            this.consume = consume;
            return this;
        }

        /**
         * Builds the options.
         *
         * @throws IllegalArgumentException if maxAgeSeconds is not positive,
         *     or the tag is empty, which would match untagged events
         */
        public VerifyOptions build() {
            if (maxAgeSeconds <= 0) {
                throw new IllegalArgumentException("maxAgeSeconds must be positive");
            }
            if (tag != null && tag.isEmpty()) {
                throw new IllegalArgumentException("tag must not be empty; leave it null to skip the check");
            }
            return new VerifyOptions(this);
        }
    }
}
