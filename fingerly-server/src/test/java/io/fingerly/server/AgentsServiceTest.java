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
    void anUnusableItemIsUnknownAndTheOthersStillDecode() {
        String body = "{\"rules_version\":\"r\",\"items\":["
                + "null,"
                + "{},"
                + "{\"ttl\":60},"
                + "{\"visitor_kind\":\"human\"},"
                + "{\"ttl\":null,\"visitor_kind\":null},"
                + "{\"ttl\":\"abc\",\"visitor_kind\":\"human\"},"
                + "{\"ttl\":1.5,\"visitor_kind\":\"human\"},"
                + "{\"ttl\":1000000000000000000000000000000,\"visitor_kind\":\"human\"},"
                + "{\"ttl\":60,\"visitor_kind\":7},"
                + "{\"ttl\":60,\"visitor_kind\":\"ai_bot\",\"ai\":\"str\"},"
                + "{\"ttl\":60,\"visitor_kind\":\"ai_bot\",\"agent\":[\"x\"]},"
                + "{\"ttl\":60,\"visitor_kind\":\"human\"}]}";
        List<AgentSighting> items =
                AgentsService.decode(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).getItems();
        assertEquals(12, items.size(), "one answer per report, in order");
        for (int i = 0; i < 11; i++) {
            assertTrue(items.get(i).isUnknown(), "item " + i);
            assertEquals("", items.get(i).getVisitorKind());
        }
        assertEquals(VisitorKind.HUMAN, items.get(11).getVisitorKind());
        assertEquals(60, items.get(11).getTtl());
    }

    @Test
    void anAiAgentMissingAFieldIsAbsentSoItsGettersNeverReturnNull() {
        String complete = "\"name\":\"GPTBot\",\"provider\":\"OpenAI\",\"purpose\":\"training\","
                + "\"runtime\":\"provider_cloud\",\"declared\":true";
        String[] broken = {
            "{\"name\":null,\"provider\":null,\"purpose\":null,\"runtime\":null,\"verification\":null}",
            "{" + complete + "}",
            "{" + complete + ",\"verification\":null}",
            "{" + complete + ",\"verification\":{\"method\":null,\"result\":\"pass\"}}",
            "{" + complete + ",\"verification\":{\"method\":\"ip_list\"}}",
            "{\"declared\":true,\"verification\":{\"method\":\"ip_list\",\"result\":\"pass\"}}",
        };
        for (String ai : broken) {
            AgentSighting answer = AgentsService.decode(("{\"rules_version\":\"r\",\"items\":[{\"ttl\":60,"
                    + "\"visitor_kind\":\"ai_bot\",\"ai\":" + ai + "}]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).getItems().get(0);
            assertEquals(VisitorKind.AI_BOT, answer.getVisitorKind());
            assertFalse(answer.getAi().isPresent(), ai);
        }
        AiAgent ai = AgentsService.decode(ANSWER.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .getItems().get(0).getAi().orElseThrow();
        assertEquals("GPTBot", ai.getName());
        assertEquals(AiVerificationResult.PASS, ai.getVerification().getResult());
    }

    @Test
    void anEmptyOrNonObjectAnswerIsAFingerlyException() throws IOException {
        for (String body : new String[] {"", "null", "[]", "\"x\"", "<html>502</html>"}) {
            try (FakeApi api = FakeApi.json(200, body)) {
                assertThrows(FingerlyException.class,
                        () -> client(api).agents().reportSightings(List.of(report())), body);
            }
        }
    }

    @Test
    void unknownIsANamedConstantThatFailsOpen() {
        assertTrue(AgentSighting.UNKNOWN.isUnknown());
        assertEquals("", AgentSighting.UNKNOWN.getVisitorKind());
        assertEquals(0, AgentSighting.UNKNOWN.getTtl());
        assertFalse(AgentSighting.UNKNOWN.getAi().isPresent());
        assertNull(AgentSighting.UNKNOWN.getAgent());
        assertFalse(VisitorKind.AI_BOT.equals(AgentSighting.UNKNOWN.getVisitorKind()));
    }
}
