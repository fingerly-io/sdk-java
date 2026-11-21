package io.fingerly.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Reports the requests your server served, so AI crawlers and fetchers that
 * never run a page's JavaScript are seen. Reached as {@code fingerly.agents()}.
 *
 * <p>This is the raw call. {@link AgentSightings} and the servlet and WebFlux
 * filters build on it: they batch, count repeats, remember the answers and
 * report off the request thread. Use this directly only to send reports you
 * collected yourself.
 *
 * <p>Never retried. A {@code 429} carries {@link FingerlyApiException#getRetryAfter()}
 * and {@link FingerlyApiException#getRateLimitScope()}.
 */
public final class AgentsService {

    /** The most requests one call may report. */
    public static final int MAX_ITEMS = 200;

    /** The largest {@link AgentSightingReport#getCount()} the API accepts. */
    public static final long MAX_COUNT = 1_000_000L;

    private final FingerlyClient client;

    AgentsService(FingerlyClient client) {
        this.client = client;
    }

    /**
     * Reports up to {@link #MAX_ITEMS} served requests and waits for what
     * sent each one.
     *
     * @return one answer per report, in the same order
     * @throws IllegalArgumentException if items is null, empty or longer than
     *     {@link #MAX_ITEMS}
     * @throws FingerlyApiException on any non-2xx answer
     * @throws FingerlyTimeoutException if the client timeout passes first
     */
    public AgentSightingsResult reportSightings(List<AgentSightingReport> items) {
        return client.await(reportSightingsAsync(items));
    }

    /** {@link #reportSightings} without holding a thread. */
    public CompletableFuture<AgentSightingsResult> reportSightingsAsync(List<AgentSightingReport> items) {
        return send(items, null);
    }

    /** {@link #reportSightingsAsync} under its own deadline rather than the client's timeout. */
    CompletableFuture<AgentSightingsResult> reportSightingsAsync(
            List<AgentSightingReport> items, Duration deadline) {
        return send(items, deadline);
    }

    private CompletableFuture<AgentSightingsResult> send(
            List<AgentSightingReport> items, Duration deadline) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("items must hold between 1 and " + MAX_ITEMS + " reports");
        }
        for (AgentSightingReport item : items) {
            if (item == null) {
                throw new IllegalArgumentException("items must not contain null");
            }
        }
        byte[] body;
        try {
            body = Json.MAPPER.writeValueAsBytes(Map.of("items", items));
        } catch (JsonProcessingException cause) {
            throw new FingerlyException("Could not encode the reports");
        }
        CompletableFuture<byte[]> pending = deadline == null
                ? client.sendAsync("POST", "/agents/sightings", body)
                : client.sendAsync("POST", "/agents/sightings", body, deadline);
        return pending.thenApply(AgentsService::decode);
    }

    /** Reads the answer, refusing one without its required fields. */
    static AgentSightingsResult decode(byte[] body) {
        JsonNode tree = Json.tree(body);
        if (!tree.path("rules_version").isTextual() || !tree.path("items").isArray()) {
            throw new FingerlyException("Fingerly API returned an unexpected AgentSightingsResult");
        }
        return Json.convert(tree, AgentSightingsResult.class);
    }

    @Override
    public String toString() {
        return "AgentsService[" + client + "]";
    }
}
