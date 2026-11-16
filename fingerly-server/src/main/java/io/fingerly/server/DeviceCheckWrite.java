package io.fingerly.server;

import java.nio.charset.StandardCharsets;

/**
 * The DeviceCheck bits to set. A bit left out keeps its value; a write names
 * at least one.
 *
 * <pre>{@code
 * DeviceCheckWrite.builder().bit0(true).build()
 * }</pre>
 */
public final class DeviceCheckWrite {

    private final Boolean bit0;
    private final Boolean bit1;

    private DeviceCheckWrite(Builder builder) {
        this.bit0 = builder.bit0;
        this.bit1 = builder.bit1;
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** The value to set for the first bit, or null to keep it. */
    public Boolean getBit0() {
        return bit0;
    }

    /** The value to set for the second bit, or null to keep it. */
    public Boolean getBit1() {
        return bit1;
    }

    /** The request body, naming only the bits that are set. */
    byte[] body() {
        StringBuilder json = new StringBuilder("{");
        if (bit0 != null) {
            json.append("\"bit0\":").append(bit0);
        }
        if (bit1 != null) {
            json.append(bit0 != null ? "," : "").append("\"bit1\":").append(bit1);
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "DeviceCheckWrite[bit0=" + bit0 + ", bit1=" + bit1 + "]";
    }

    /** Builds a {@link DeviceCheckWrite}. */
    public static final class Builder {
        private Boolean bit0;
        private Boolean bit1;

        private Builder() {}

        /** Sets the first bit. */
        public Builder bit0(boolean value) {
            this.bit0 = value;
            return this;
        }

        /** Sets the second bit. */
        public Builder bit1(boolean value) {
            this.bit1 = value;
            return this;
        }

        /**
         * Builds the write.
         *
         * @throws IllegalArgumentException if neither bit is set
         */
        public DeviceCheckWrite build() {
            if (bit0 == null && bit1 == null) {
                throw new IllegalArgumentException("name bit0, bit1 or both");
            }
            return new DeviceCheckWrite(this);
        }
    }
}
