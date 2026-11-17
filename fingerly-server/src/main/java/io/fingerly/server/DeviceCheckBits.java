package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * The two Apple DeviceCheck bits of an event's device, as Apple holds them.
 *
 * <p>The bits mean what you decide; Fingerly never reads or keeps them.
 */
public final class DeviceCheckBits {

    private final boolean found;
    private final boolean bit0;
    private final boolean bit1;
    private final String lastUpdate;

    private DeviceCheckBits(boolean found, boolean bit0, boolean bit1, String lastUpdate) {
        this.found = found;
        this.bit0 = bit0;
        this.bit1 = bit1;
        this.lastUpdate = lastUpdate;
    }

    static DeviceCheckBits from(JsonNode tree) {
        JsonNode lastUpdate = tree.path("last_update");
        return new DeviceCheckBits(
                tree.path("found").asBoolean(false),
                tree.path("bit0").asBoolean(false),
                tree.path("bit1").asBoolean(false),
                lastUpdate.isTextual() && !lastUpdate.asText().isEmpty() ? lastUpdate.asText() : null);
    }

    /** False when Apple holds no bits for the device yet; both bits are then false. */
    public boolean isFound() {
        return found;
    }

    /** The first bit. */
    public boolean getBit0() {
        return bit0;
    }

    /** The second bit. */
    public boolean getBit1() {
        return bit1;
    }

    /** When the bits last changed, as {@code YYYY-MM}; empty when Apple holds none. */
    public Optional<String> getLastUpdate() {
        return Optional.ofNullable(lastUpdate);
    }

    @Override
    public String toString() {
        return "DeviceCheckBits[found=" + found + ", bit0=" + bit0 + ", bit1=" + bit1
                + ", lastUpdate=" + lastUpdate + "]";
    }
}
