package io.fingerly.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

/** The answer to one {@link AgentsService#reportSightings} call. */
public final class AgentSightingsResult {

    @JsonProperty("rules_version")
    private String rulesVersion;

    private List<AgentSighting> items;

    AgentSightingsResult() {}

    /**
     * Names the classification rules the answers came from. When it changes,
     * every answer remembered under the old rules is stale.
     */
    public String getRulesVersion() {
        return rulesVersion;
    }

    /** One answer per reported request, in the order they were reported. */
    public List<AgentSighting> getItems() {
        return items == null ? List.of() : Collections.unmodifiableList(items);
    }

    @Override
    public String toString() {
        return "AgentSightingsResult[rulesVersion=" + rulesVersion + ", items="
                + (items == null ? 0 : items.size()) + "]";
    }
}
