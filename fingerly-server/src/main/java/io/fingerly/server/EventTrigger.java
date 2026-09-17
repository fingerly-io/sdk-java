package io.fingerly.server;

/** One signal group that contributed to an event's suspect score. */
public final class EventTrigger {

    private String signal;
    private int weight;
    private String confidence;

    EventTrigger() {}

    /** The signal group, such as {@code vpn} or {@code bot}. */
    public String getSignal() {
        return signal;
    }

    /** The group's combined weight in this score. */
    public int getWeight() {
        return weight;
    }

    /** {@code low}, {@code medium} or {@code high}. */
    public String getConfidence() {
        return confidence;
    }

    @Override
    public String toString() {
        return "EventTrigger[" + signal + "=" + weight + "]";
    }
}
