package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** The DeviceCheck bits Apple accepted on a write. */
public final class DeviceCheckWriteResult {

    private final Boolean bit0;
    private final Boolean bit1;

    private DeviceCheckWriteResult(Boolean bit0, Boolean bit1) {
        this.bit0 = bit0;
        this.bit1 = bit1;
    }

    static DeviceCheckWriteResult from(JsonNode tree) {
        return new DeviceCheckWriteResult(bit(tree.path("bit0")), bit(tree.path("bit1")));
    }

    private static Boolean bit(JsonNode node) {
        return node.isBoolean() ? node.asBoolean() : null;
    }

    /** The value set for the first bit; empty when the write left it out. */
    public Optional<Boolean> getBit0() {
        return Optional.ofNullable(bit0);
    }

    /** The value set for the second bit; empty when the write left it out. */
    public Optional<Boolean> getBit1() {
        return Optional.ofNullable(bit1);
    }

    @Override
    public String toString() {
        return "DeviceCheckWriteResult[bit0=" + bit0 + ", bit1=" + bit1 + "]";
    }
}
