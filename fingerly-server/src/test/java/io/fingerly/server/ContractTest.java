package io.fingerly.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Server-owned fixtures: the SDK must read exactly what the API sends.
 *
 * <p>The vendored snapshot keeps these tests independent of sibling
 * repositories. Refresh it with {@code java tools/SyncContract.java}.
 */
class ContractTest {

    private static final Path CONTRACT =
            Path.of(System.getProperty("fingerly.contract.dir", "../contract"));

    private static JsonNode fixtures() throws IOException {
        return Json.MAPPER.readTree(Files.readAllBytes(CONTRACT.resolve("fixtures.json")));
    }

    private static FingerlyClient client(FakeApi api) {
        return FingerlyClient.builder()
                .secretKey("fly_sk_development_contract")
                .allowInsecureLoopback(true)
                .endpoint(api.url())
                .build();
    }

    private static List<JsonNode> responsesOf(String schema) throws IOException {
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode fixture : fixtures().path("responses")) {
            if (schema.equals(fixture.path("schema").asText())) {
                selected.add(fixture);
            }
        }
        assertFalse(selected.isEmpty(), "no fixtures for " + schema);
        return selected;
    }

    @Test
    void snapshotMatchesItsManifest() throws Exception {
        JsonNode manifest = Json.MAPPER.readTree(
                Files.readAllBytes(CONTRACT.resolve("manifest.json")));
        JsonNode files = manifest.path("files");
        assertTrue(files.size() > 0, "the manifest records no files");

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        var names = files.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            byte[] content = Files.readAllBytes(CONTRACT.resolve(name));
            StringBuilder hex = new StringBuilder();
            for (byte b : sha256.digest(content)) {
                hex.append(String.format("%02x", b));
            }
            assertEquals(files.path(name).asText(), hex.toString(),
                    name + " is stale; run tools/SyncContract.java");
            sha256.reset();
        }
    }

    @TestFactory
    List<DynamicTest> readsEveryEventFixture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode fixture : responsesOf("SDKEventOut")) {
            tests.add(DynamicTest.dynamicTest(fixture.path("name").asText(), () -> {
                JsonNode want = fixture.path("body");
                try (FakeApi api = FakeApi.json(fixture.path("status").asInt(), want.toString())) {
                    Event event = client(api).events().get("id");

                    assertEquals(want.path("request_id").asText(), event.getRequestId());
                    assertEquals(want.path("visitor_id").asText(), event.getVisitorId());
                    assertNotNull(event.getOccurredAt(), "occurred_at did not decode");
                    JsonNode project = want.path("project_id");
                    assertEquals(project.isTextual() ? project.asText() : null,
                            event.getProjectId());

                    // A fixture without a score must not decode as zero.
                    JsonNode score = want.path("suspect_score");
                    if (score.isMissingNode() || score.isNull()) {
                        assertFalse(event.isScored(), "an unscored fixture decoded as scored");
                    } else {
                        assertTrue(event.isScored());
                        assertEquals(score.asInt(), event.getSuspectScore().orElseThrow());
                    }
                    assertEquals(want.path("triggers").size(), event.getTriggers().size());

                    JsonNode kind = want.path("visitor_kind");
                    assertEquals(kind.isTextual() ? kind.asText() : null, event.getVisitorKind());
                    // ai is absent on every row that is not an AI agent.
                    assertEquals(want.path("ai").isObject(), event.getAi().isPresent());
                }
            }));
        }
        return tests;
    }

    private static JsonNode responseNamed(String name) throws IOException {
        for (JsonNode fixture : fixtures().path("responses")) {
            if (name.equals(fixture.path("name").asText())) {
                return fixture;
            }
        }
        throw new AssertionError("no fixture named " + name);
    }

    @Test
    void readsTheAiAgentFixture() throws IOException {
        JsonNode fixture = responseNamed("ai_agent");
        JsonNode want = fixture.path("body").path("ai");
        try (FakeApi api = FakeApi.json(200, fixture.path("body").toString())) {
            Event event = client(api).events().get("id");

            assertEquals(VisitorKind.AI_BOT, event.getVisitorKind());
            AiAgent ai = event.getAi().orElseThrow();
            assertEquals(want.path("name").asText(), ai.getName());
            assertEquals("ChatGPT-User", ai.getName());
            assertEquals("OpenAI", ai.getProvider());
            assertEquals(AiPurpose.USER_FETCH, ai.getPurpose());
            assertEquals(AiRuntime.PROVIDER_CLOUD, ai.getRuntime());
            assertTrue(ai.isDeclared());
            assertEquals(AiVerificationMethod.NONE, ai.getVerification().getMethod());
            assertEquals(AiVerificationResult.NOT_ATTEMPTED, ai.getVerification().getResult());
        }
    }

    @Test
    void readsAnEventWithoutAi() throws IOException {
        JsonNode fixture = responseNamed("event");
        assertTrue(fixture.path("body").path("ai").isMissingNode());
        try (FakeApi api = FakeApi.json(200, fixture.path("body").toString())) {
            assertFalse(client(api).events().get("id").getAi().isPresent());
        }
    }

    @Test
    void readsAiValuesThisSdkDoesNotKnow() throws IOException {
        ObjectNode body = (ObjectNode) responseNamed("ai_agent").path("body").deepCopy();
        ObjectNode ai = (ObjectNode) body.path("ai");
        ai.put("purpose", "future_purpose");
        ai.put("runtime", "future_runtime");
        ((ObjectNode) ai.path("verification"))
                .put("method", "future_method").put("result", "future_result");
        body.put("visitor_kind", "future_kind");
        try (FakeApi api = FakeApi.json(200, body.toString())) {
            Event event = client(api).events().get("id");

            assertEquals("future_kind", event.getVisitorKind());
            AiAgent decoded = event.getAi().orElseThrow();
            assertEquals("future_purpose", decoded.getPurpose());
            assertEquals("future_runtime", decoded.getRuntime());
            assertEquals("future_method", decoded.getVerification().getMethod());
            assertEquals("future_result", decoded.getVerification().getResult());
        }
    }

    @TestFactory
    List<DynamicTest> readsEveryPageFixture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode fixture : responsesOf("SDKEventsOut")) {
            tests.add(DynamicTest.dynamicTest(fixture.path("name").asText(), () -> {
                JsonNode want = fixture.path("body");
                try (FakeApi api = FakeApi.json(fixture.path("status").asInt(), want.toString())) {
                    EventPage page = client(api).events().list();

                    assertEquals(want.path("page").asInt(), page.getPage());
                    assertEquals(want.path("page_size").asInt(), page.getPageSize());
                    assertEquals(want.path("rows").size(), page.getRows().size());
                    for (int i = 0; i < page.getRows().size(); i++) {
                        assertEquals(want.path("rows").get(i).path("request_id").asText(),
                                page.getRows().get(i).getRequestId());
                        JsonNode project = want.path("rows").get(i).path("project_id");
                        assertEquals(project.isTextual() ? project.asText() : null,
                                page.getRows().get(i).getProjectId());
                    }
                }
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> mapsEveryErrorFixture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode fixture : responsesOf("ErrorResponse")) {
            tests.add(DynamicTest.dynamicTest(fixture.path("name").asText(), () -> {
                JsonNode want = fixture.path("body");
                int status = fixture.path("status").asInt();
                try (FakeApi api = FakeApi.json(status, want.toString())) {
                    FingerlyApiException failure = assertThrows(FingerlyApiException.class,
                            () -> client(api).events().get("id"));

                    assertEquals(status, failure.getStatus());
                    assertEquals(want.path("error").path("code").asText(), failure.getCode());
                    assertEquals(want.path("request_id").asText(), failure.getRequestId());
                }
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> verifiesEveryWebhookFixture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode fixture : fixtures().path("webhooks")) {
            tests.add(DynamicTest.dynamicTest(fixture.path("name").asText(), () -> {
                // Verify each fixture at the instant it was signed.
                Instant signedAt = Instant.ofEpochSecond(fixture.path("timestamp").asLong());
                boolean verified = Webhooks.verify(
                        fixture.path("secret").asText(),
                        fixture.path("payload").asText().getBytes(StandardCharsets.UTF_8),
                        fixture.path("timestamp").asText(),
                        fixture.path("signature").asText(),
                        Webhooks.DEFAULT_TOLERANCE,
                        signedAt);
                assertEquals(fixture.path("valid").asBoolean(), verified);
            }));
        }
        return tests;
    }

    @Test
    void exposesTheWebhookProjectIdInData() throws IOException {
        // Webhook data stays a tree; project_id is read from it like any other member.
        JsonNode fixture = fixtures().path("webhooks").get(0);
        WebhookEvent event = Webhooks.parse(
                fixture.path("payload").asText().getBytes(StandardCharsets.UTF_8));
        JsonNode want = Json.MAPPER.readTree(fixture.path("payload").asText())
                .path("data").path("project_id");

        assertTrue(want.isTextual(), "the webhook fixture carries no project_id");
        assertEquals(want.asText(), event.getData().path("project_id").asText());
    }

    @Test
    void refusesAStaleWebhookFixture() throws IOException {
        JsonNode fixture = fixtures().path("webhooks").get(0);
        Instant stale = Instant.ofEpochSecond(fixture.path("timestamp").asLong())
                .plus(Duration.ofSeconds(301));

        assertFalse(Webhooks.verify(
                fixture.path("secret").asText(),
                fixture.path("payload").asText().getBytes(StandardCharsets.UTF_8),
                fixture.path("timestamp").asText(),
                fixture.path("signature").asText(),
                Webhooks.DEFAULT_TOLERANCE,
                stale));
    }

    @Test
    void v3ContractRetiresSupplementAndModelsSignalsAndEmptyVisitorId() throws IOException {
        JsonNode openapi = Json.MAPPER.readTree(Files.readAllBytes(CONTRACT.resolve("openapi.json")));
        assertTrue(openapi.path("paths").path("/api/v1/events/{request_id}/supplement").isMissingNode());
        assertTrue(openapi.path("components").path("schemas").path("SupplementOut").isMissingNode());
        assertTrue(openapi.path("components").path("schemas").path("IdentifyOut")
                .path("properties").path("deferred_token").isMissingNode());

        String unidentifiable = "{"
                + "\"request_id\":\"0199a963-e400-7000-8000-000000000099\","
                + "\"visitor_id\":\"\","
                + "\"occurred_at\":\"2026-09-17T12:00:00Z\","
                + "\"state\":\"unavailable\","
                + "\"reason\":\"enrichment_unavailable\","
                + "\"environment\":\"production\","
                + "\"platform\":\"android\","
                + "\"sdk_platform\":\"android\","
                + "\"sdk_version\":\"0.3.0\","
                + "\"suspect_score\":48,"
                + "\"suspect_level\":\"high\","
                + "\"triggers\":["
                + "{\"signal\":\"attestation_failed\",\"weight\":30,\"confidence\":\"high\"},"
                + "{\"signal\":\"device_inconsistent\",\"weight\":18,\"confidence\":\"high\"},"
                + "{\"signal\":\"report_incomplete\",\"weight\":16,\"confidence\":\"medium\"},"
                + "{\"signal\":\"tampering\",\"weight\":15,\"confidence\":\"high\"},"
                + "{\"signal\":\"custom_root_ca\",\"weight\":0,\"confidence\":\"low\"},"
                + "{\"signal\":\"secondary_user_profile\",\"weight\":0,\"confidence\":\"high\"},"
                + "{\"signal\":\"attestation_downgrade\",\"weight\":30,\"confidence\":\"high\"},"
                + "{\"signal\":\"probe_errors\",\"weight\":4,\"confidence\":\"low\"},"
                + "{\"signal\":\"fetch_metadata_missing\",\"weight\":2,\"confidence\":\"low\"},"
                + "{\"signal\":\"web_ticket_missing\",\"weight\":4,\"confidence\":\"low\"},"
                + "{\"signal\":\"web_ticket_invalid\",\"weight\":20,\"confidence\":\"high\"}"
                + "],"
                + "\"has_document\":false"
                + "}";
        assertEquals("attestation_downgrade", EventTrigger.SIGNAL_ATTESTATION_DOWNGRADE);
        assertEquals("probe_errors", EventTrigger.SIGNAL_PROBE_ERRORS);
        assertEquals("fetch_metadata_missing", EventTrigger.SIGNAL_FETCH_METADATA_MISSING);
        assertEquals("web_ticket_missing", EventTrigger.SIGNAL_WEB_TICKET_MISSING);
        assertEquals("web_ticket_invalid", EventTrigger.SIGNAL_WEB_TICKET_INVALID);
        try (FakeApi api = FakeApi.json(200, unidentifiable)) {
            Event event = client(api).events().get("0199a963-e400-7000-8000-000000000099");
            assertEquals("", event.getVisitorId());
            assertEquals("enrichment_unavailable", event.getReason());
            assertEquals(11, event.getTriggers().size());
            for (EventTrigger trigger : event.getTriggers()) {
                assertTrue(EventTrigger.V3_SIGNALS.contains(trigger.getSignal()),
                        trigger.getSignal());
            }
        }
    }
}
