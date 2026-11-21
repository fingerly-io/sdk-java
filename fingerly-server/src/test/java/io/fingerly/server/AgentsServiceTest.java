package io.fingerly.server;

import static io.fingerly.server.Fixtures.SECRET_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentsServiceTest {

    private static final String ANSWER =
            "{\"rules_version\":\"r1\",\"items\":[{\"ttl\":900,\"visitor_kind\":\"ai_bot\","
                    + "\"agent\":\"GPTBot\",\"ai\":{\"name\":\"GPTBot\",\"provider\":\"OpenAI\","
                    + "\"purpose\":\"training\",\"runtime\":\"provider_cloud\",\"declared\":true,"
                    + "\"verification\":{\"method\":\"ip_list\",\"result\":\"pass\"}}}]}";

    private static FingerlyClient client(FakeApi api) {
        return FingerlyClient.builder().secretKey(SECRET_KEY).allowInsecureLoopback(true)
                .endpoint(api.url()).build();
    }

    private static AgentSightingReport report() {
        return AgentSightingReport.builder()
                .userAgent("GPTBot/1.4")
                .clientIp("20.171.206.9")
                .host("example.com")
                .method("GET")
                .count(3)
                .build();
    }

    @Test
    void postsTheReportsWithTheSecretKey() throws IOException {
        try (FakeApi api = FakeApi.json(200, ANSWER)) {
            AgentSightingsResult result = client(api).agents().reportSightings(List.of(report()));

            FakeApi.Recorded sent = api.first();
            assertEquals("POST", sent.method);
            assertEquals("/api/v1/agents/sightings", sent.path);
            assertEquals(SECRET_KEY, sent.header("X-API-Key"));
            assertEquals("application/json", sent.header("Content-Type"));
            JsonNode body = Json.MAPPER.readTree(sent.body);
            JsonNode item = body.path("items").path(0);
            assertEquals("GPTBot/1.4", item.path("user_agent").asText());
            assertEquals("20.171.206.9", item.path("client_ip").asText());
            assertEquals("example.com", item.path("host").asText());
            assertEquals("GET", item.path("method").asText());
            assertEquals(3, item.path("count").asLong());
            // Absent headers are left out, never sent as null.
            assertFalse(item.has("signature"));
            assertFalse(item.has("signature_input"));
            assertFalse(item.has("signature_agent"));

            assertEquals("r1", result.getRulesVersion());
            AgentSighting answer = result.getItems().get(0);
            assertEquals(VisitorKind.AI_BOT, answer.getVisitorKind());
            assertEquals("GPTBot", answer.getAgent());
            assertEquals(900, answer.getTtl());
        }
    }

    @Test
    void anAbsentUserAgentIsSentEmpty() throws IOException {
        try (FakeApi api = FakeApi.json(200, ANSWER)) {
            client(api).agents().reportSightings(
                    List.of(AgentSightingReport.builder().clientIp("198.51.100.1").build()));
            JsonNode item = Json.MAPPER.readTree(api.first().body).path("items").path(0);
            assertEquals("", item.path("user_agent").asText());
            assertTrue(item.path("user_agent").isTextual());
            assertEquals(1, item.path("count").asLong());
        }
    }

    @Test
    void refusesAnEmptyOrOversizedBatchBeforeSending() throws IOException {
        try (FakeApi api = FakeApi.json(200, ANSWER)) {
            AgentsService agents = client(api).agents();
            assertThrows(IllegalArgumentException.class, () -> agents.reportSightings(List.of()));
            assertThrows(IllegalArgumentException.class, () -> agents.reportSightings(null));
            List<AgentSightingReport> many = new ArrayList<>(Collections.nCopies(201, report()));
            assertThrows(IllegalArgumentException.class, () -> agents.reportSightings(many));
            assertEquals(0, api.callCount());
        }
    }

    @Test
    void reportValidatesItsFields() {
        assertThrows(IllegalArgumentException.class, () -> AgentSightingReport.builder().build());
        assertThrows(IllegalArgumentException.class,
                () -> AgentSightingReport.builder().clientIp("192.0.2.1").count(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> AgentSightingReport.builder().clientIp("192.0.2.1").count(1_000_001).build());
        assertFalse(report().toString().contains("GPTBot"), "a report must not print its fields");
    }

    @Test
    void aRateLimitCarriesRetryAfterAndScope() throws IOException {
        try (FakeApi api = FakeApi.start(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "3600");
            exchange.getResponseHeaders().add("Fingerly-RateLimit-Scope", "daily");
            FakeApi.respond(exchange, 429, Fixtures.error("rate_limited", 429));
        })) {
            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).agents().reportSightings(List.of(report())));
            assertEquals(429, failure.getStatus());
            assertEquals("rate_limited", failure.getCode());
            assertEquals(3600, failure.getRetryAfter());
            assertEquals("daily", failure.getRateLimitScope());
        }
    }

    @Test
    void anInvalidRequestIsAnApiError() throws IOException {
        try (FakeApi api = FakeApi.json(422, Fixtures.error("invalid_request", 422))) {
            FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                    () -> client(api).agents().reportSightings(List.of(report())));
            assertEquals(422, failure.getStatus());
            assertEquals("invalid_request", failure.getCode());
            assertNull(failure.getRateLimitScope());
        }
    }

    @Test
    void anAnswerWithoutRulesVersionIsRefused() throws IOException {
        try (FakeApi api = FakeApi.json(200, "{\"items\":[]}")) {
            assertThrows(FingerlyException.class,
                    () -> client(api).agents().reportSightings(List.of(report())));
        }
    }

    @Test
    void unknownValuesStillDecode() throws IOException {
        String body = "{\"rules_version\":\"r\",\"items\":[{\"ttl\":5,\"visitor_kind\":\"future_kind\","
                + "\"extra\":1}]}";
        try (FakeApi api = FakeApi.json(200, body)) {
            AgentSighting answer =
                    client(api).agents().reportSightings(List.of(report())).getItems().get(0);
            assertEquals("future_kind", answer.getVisitorKind());
            assertFalse(answer.getAi().isPresent());
            assertNull(answer.getAgent());
        }
    }

    @Test
    void unknownIsANamedConstantThatFailsOpen() {
        assertTrue(AgentSighting.UNKNOWN.isUnknown());
        assertEquals("unknown", AgentSighting.UNKNOWN.getVisitorKind());
        assertEquals(0, AgentSighting.UNKNOWN.getTtl());
        assertFalse(AgentSighting.UNKNOWN.getAi().isPresent());
        assertNull(AgentSighting.UNKNOWN.getAgent());
        assertFalse(VisitorKind.AI_BOT.equals(AgentSighting.UNKNOWN.getVisitorKind()));
    }
}
