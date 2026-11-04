package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * The envelope every webhook delivery shares.
 *
 * <p>{@link #getData()} is left as a tree, so a new event type needs no SDK
 * upgrade. Convert it to the shape that event documents.
 */
public final class WebhookEvent {

    private String id;
    private String type;
    private int version;
    private String organizationId;
    private String environment;
    private Instant createdAt;
    private JsonNode data;

    WebhookEvent() {}

    /**
     * Identifies this delivery. Persist it to deduplicate: a webhook may
     * arrive more than once.
     */
    public String getId() {
        return id;
    }

    /** The event name, such as {@code identification.completed}. */
    public String getType() {
        return type;
    }

    /** The envelope version. */
    public int getVersion() {
        return version;
    }

    /** The organization the event belongs to. */
    public String getOrganizationId() {
        return organizationId;
    }

    /** The deployment the event came from. */
    public String getEnvironment() {
        return environment;
    }

    /** When Fingerly recorded the event. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** The event-specific payload. */
    public JsonNode getData() {
        return data;
    }

    /**
     * {@link #getData()} as JSON text, or null when the event carried none.
     * Use this where the value is handed to Jackson 3 (Spring Boot 4), which
     * cannot serialize a Jackson 2 node.
     */
    public String getDataJson() {
        return data == null || data.isNull() ? null : data.toString();
    }

    @Override
    public String toString() {
        return "WebhookEvent[id=" + id + ", type=" + type + "]";
    }
}
