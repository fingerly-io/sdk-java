package io.fingerly.server;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;

/** One page of events, newest first. */
public final class EventPage {

    private List<Event> rows;
    private int page;

    @JsonProperty("page_size")
    private int pageSize;

    @JsonIgnore
    private transient JsonNode raw;
    private transient String rawJson;

    EventPage() {}

    void setRaw(JsonNode raw, String rawJson) {
        this.raw = raw;
        this.rawJson = rawJson;
    }

    /** The events on this page. Never null; possibly empty. */
    public List<Event> getRows() {
        return rows == null ? Collections.emptyList() : Collections.unmodifiableList(rows);
    }

    /** The one-based page number returned. */
    public int getPage() {
        return page;
    }

    /** Rows per page the server applied. */
    public int getPageSize() {
        return pageSize;
    }

    /** The response body exactly as it arrived. */
    public JsonNode getRaw() {
        return raw;
    }

    /**
     * The response body as the JSON text it arrived as.
     *
     * <p>{@link #getRaw()} is a Jackson 2 {@code JsonNode}. Spring Boot 4
     * serializes controller results with Jackson 3, which does not know that
     * type and writes its accessors instead of the JSON, so return or embed
     * this string rather than the node.
     */
    public String getRawJson() {
        return rawJson;
    }

    @Override
    public String toString() {
        return "EventPage[page=" + page + ", pageSize=" + pageSize + ", rows=" + getRows().size() + "]";
    }
}
