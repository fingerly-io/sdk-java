package io.fingerly.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhooksTest {

    private static final String TIMESTAMP = "1789646400";
    private static final byte[] PAYLOAD =
            "{\"id\":\"evt_1\",\"type\":\"identification.completed\"}".getBytes(StandardCharsets.UTF_8);

    /** Signs a payload exactly as the Fingerly webhook worker does. */
    private static String signWith(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((TIMESTAMP + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(payload);
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : mac.doFinal()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException cause) {
            throw new IllegalStateException(cause);
        }
    }

    /** An instant offset from the fixture timestamp. */
    private static Instant at(long seconds) {
        return Instant.ofEpochSecond(1789646400L + seconds);
    }

    private static boolean verifyAt(String secret, byte[] payload, String timestamp,
            String signature, Instant now) {
        return Webhooks.verify(secret, payload, timestamp, signature, Webhooks.DEFAULT_TOLERANCE, now);
    }

    @Test
    void acceptsAGenuineDelivery() {
        assertTrue(verifyAt("secret", PAYLOAD, TIMESTAMP, signWith("secret", PAYLOAD), at(0)));
    }

    @Test
    void refusesTampering() {
        String signature = signWith("secret", PAYLOAD);
        byte[] other = "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);

        assertFalse(verifyAt("secret", other, TIMESTAMP, signature, at(0)), "body");
        assertFalse(verifyAt("other", PAYLOAD, TIMESTAMP, signature, at(0)), "secret");
        assertFalse(verifyAt("secret", PAYLOAD, "1789646401", signature, at(0)), "timestamp");
    }

    @Test
    void acceptsAnySignatureDuringRotation() {
        String header = signWith("new", PAYLOAD) + "," + signWith("old", PAYLOAD);

        assertTrue(verifyAt("new", PAYLOAD, TIMESTAMP, header, at(0)));
        assertTrue(verifyAt("old", PAYLOAD, TIMESTAMP, header, at(0)));
        assertFalse(verifyAt("other", PAYLOAD, TIMESTAMP, header, at(0)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 299, -299})
    void acceptsATimestampWithinTolerance(long offset) {
        assertTrue(verifyAt("secret", PAYLOAD, TIMESTAMP, signWith("secret", PAYLOAD), at(offset)));
    }

    @ParameterizedTest
    @ValueSource(longs = {301, -301})
    void refusesATimestampOutsideTolerance(long offset) {
        assertFalse(verifyAt("secret", PAYLOAD, TIMESTAMP, signWith("secret", PAYLOAD), at(offset)));
    }

    @Test
    void honoursACustomTolerance() {
        String signature = signWith("secret", PAYLOAD);
        assertTrue(Webhooks.verify("secret", PAYLOAD, TIMESTAMP, signature,
                Duration.ofMinutes(10), at(400)));
        assertFalse(Webhooks.verify("secret", PAYLOAD, TIMESTAMP, signature,
                Duration.ofSeconds(-1), at(0)), "a negative tolerance");
        assertFalse(Webhooks.verify("secret", PAYLOAD, TIMESTAMP, signature, null, at(0)),
                "a null tolerance");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1789646400.5", "-1", "0x10", "Infinity", "1789646400\n"})
    void refusesANoncanonicalTimestamp(String timestamp) {
        assertFalse(verifyAt("secret", PAYLOAD, timestamp, signWith("secret", PAYLOAD), at(0)));
    }

    @Test
    void refusesAMalformedSignature() {
        String zeros = "0".repeat(64);
        for (String candidate : new String[] {
            "", "sha256=", "sha256=" + "0".repeat(63), "sha256=" + "F".repeat(64),
            "md5=" + zeros, zeros,
        }) {
            assertFalse(verifyAt("secret", PAYLOAD, TIMESTAMP, candidate, at(0)), candidate);
        }
    }

    @Test
    void refusesMissingInputRatherThanThrowing() {
        String signature = signWith("secret", PAYLOAD);
        assertFalse(verifyAt(null, PAYLOAD, TIMESTAMP, signature, at(0)), "null secret");
        assertFalse(verifyAt("", PAYLOAD, TIMESTAMP, signature, at(0)), "empty secret");
        assertFalse(verifyAt("secret", null, TIMESTAMP, signature, at(0)), "null payload");
        assertFalse(verifyAt("secret", PAYLOAD, null, signature, at(0)), "null timestamp");
        assertFalse(verifyAt("secret", PAYLOAD, TIMESTAMP, null, at(0)), "null signature");
    }

    @Test
    void anEmptyBodyIsStillSignable() {
        byte[] empty = new byte[0];
        assertTrue(verifyAt("secret", empty, TIMESTAMP, signWith("secret", empty), at(0)));
    }

    @Test
    void parsesTheEnvelope() {
        byte[] body = ("{"
                + "\"id\":\"01a0a851-0c4e-7f23-b8d1-6e2f94c0a7b5\","
                + "\"type\":\"identification.completed\","
                + "\"version\":1,"
                + "\"organization_id\":\"01a0a7f2-3c18-7b40-8d2e-5f6a9b1c0d37\","
                + "\"environment\":\"live\","
                + "\"created_at\":\"2026-09-16T09:41:12Z\","
                + "\"data\":{\"request_id\":\"01a0a84b\",\"level\":\"high\"}"
                + "}").getBytes(StandardCharsets.UTF_8);

        WebhookEvent event = Webhooks.parse(body);

        assertEquals("01a0a851-0c4e-7f23-b8d1-6e2f94c0a7b5", event.getId());
        assertEquals("identification.completed", event.getType());
        assertEquals(1, event.getVersion());
        assertEquals("01a0a7f2-3c18-7b40-8d2e-5f6a9b1c0d37", event.getOrganizationId());
        assertEquals("{\"request_id\":\"01a0a84b\",\"level\":\"high\"}", event.getDataJson());
        assertEquals("live", event.getEnvironment());
        assertEquals(Instant.parse("2026-09-16T09:41:12Z"), event.getCreatedAt());
        // Data stays a tree, so a new event type needs no SDK upgrade.
        assertEquals("high", event.getData().path("level").asText());
    }

    @Test
    void refusesToParseSomethingThatIsNotAnEnvelope() {
        assertThrows(FingerlyException.class,
                () -> Webhooks.parse("not json".getBytes(StandardCharsets.UTF_8)));
    }
}
