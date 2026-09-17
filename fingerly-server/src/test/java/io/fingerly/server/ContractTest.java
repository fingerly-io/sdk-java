package io.fingerly.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
                .secretKey("fly_sk_us_development_contract")
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

                    // A fixture without a score must not decode as zero.
                    JsonNode score = want.path("suspect_score");
                    if (score.isMissingNode() || score.isNull()) {
                        assertFalse(event.isScored(), "an unscored fixture decoded as scored");
                    } else {
                        assertTrue(event.isScored());
                        assertEquals(score.asInt(), event.getSuspectScore().orElseThrow());
                    }
                    assertEquals(want.path("triggers").size(), event.getTriggers().size());
                }
            }));
        }
        return tests;
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
}
