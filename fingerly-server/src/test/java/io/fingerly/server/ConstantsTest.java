package io.fingerly.server;

import static io.fingerly.server.Fixtures.REQUEST_ID;
import static io.fingerly.server.Fixtures.SECRET_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins every named constant to the exact string the server sends.
 *
 * <p>The expected lists are copied from the server's own definitions (the risk
 * signal catalogue and its groups, the suspect level bands, the attestation
 * report, the webhook event types). A constant added here without a server
 * value, or a server value missing here, fails.
 */
class ConstantsTest {

    /** Every detector the server's risk catalogue defines. */
    private static final List<String> SERVER_DETECTORS = Arrays.asList(
            "high_activity", "tampering", "device_farm", "developer_tools", "geo_mismatch",
            "device_inconsistent", "tor", "proxy", "residential_proxy",
            "residential_proxy_low_confidence", "residential_proxy_medium_confidence",
            "residential_proxy_high_confidence", "vpn_public", "vpn_timezone", "vpn_relay",
            "vpn_mobile", "vpn_os_mismatch", "vpn_low_confidence", "vpn_medium_confidence",
            "vpn_high_confidence", "ip_blocklist_email_spam", "ip_blocklist_attack_source",
            "ip_abuser", "datacenter_ip", "bogon_ip", "anycast_ip", "incognito", "bad_bot",
            "automation", "fetch_metadata_missing", "web_ticket_missing", "web_ticket_invalid",
            "antidetect_browser", "virtual_machine", "fingerprint_suppressed", "privacy_settings",
            "embedded_web_view", "remote_control", "emulator", "root_apps", "app_cloner",
            "jailbreak", "simulator", "frida", "mitm_attack", "active_call", "attestation_missing",
            "attestation_downgrade", "repeated_reinstall", "device_activity_high",
            "device_activity_very_high", "play_device_integrity_failed", "play_app_unrecognized",
            "attestation_failed", "report_incomplete", "probe_errors", "custom_root_ca",
            "secondary_user_profile");

    /** Every group the server reports stored event triggers under. */
    private static final List<String> SERVER_GROUPS = Arrays.asList(
            "tor", "datacenter_proxy", "residential_proxy", "vpn", "ip_blocklist", "ip_reputation",
            "network_anomaly", "location_spoofing", "ios_simulator", "android_emulator",
            "virtual_machine", "jailbroken_device", "rooted_device", "cloned_app", "device_farm",
            "mitm_attack", "frida_detected", "browser_tampering", "fingerprint_suppressed",
            "developer_tools", "remote_control", "bot", "high_activity", "incognito_mode",
            "privacy_settings", "embedded_web_view", "active_call", "attestation_missing",
            "attestation_failed", "device_inconsistent", "report_incomplete", "custom_root_ca",
            "secondary_user_profile");

    /** Reads a constants class as name to value, failing on anything that is not a String constant. */
    private static Map<String, String> constants(Class<?> type) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.isSynthetic()) {
                continue;
            }
            assertTrue(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers) && field.getType() == String.class,
                    type.getSimpleName() + "." + field.getName() + " is not a public String constant");
            try {
                out.put(field.getName(), (String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return out;
    }

    /** Asserts each constant is named after its value and the values are exactly the expected ones. */
    private static void assertConstants(Class<?> type, String... expected) {
        Map<String, String> constants = constants(type);
        assertEquals(new HashSet<>(Arrays.asList(expected)), new HashSet<>(constants.values()),
                type.getSimpleName());
        assertEquals(expected.length, constants.size(), type.getSimpleName() + " has a duplicate");
        constants.forEach((name, value) -> {
            String derived = value.isEmpty() ? "OK" : value.toUpperCase(Locale.ROOT)
                    .replace('.', '_');
            assertEquals(derived, name, type.getSimpleName() + "." + name);
        });
    }

    @Test
    void signalCoversEveryServerDetectorAndGroup() {
        Set<String> expected = new HashSet<>(SERVER_DETECTORS);
        expected.addAll(SERVER_GROUPS);
        assertEquals(73, expected.size());
        assertConstants(Signal.class, expected.toArray(new String[0]));
    }

    @Test
    void closedSetsMatchTheServer() {
        assertConstants(SuspectLevel.class, "low", "medium", "high");
        assertConstants(Confidence.class, "low", "medium", "high");
        assertConstants(AttestationState.class, "attested", "not_attested", "failed");
        assertConstants(AttestationStatus.class,
                "verified", "indeterminate", "invalid", "absent", "unconfigured");
        assertConstants(AttestSkippedReason.class, "not_offered", "unsupported",
                "challenge_unavailable", "key_error", "timeout", "backoff", "install_unavailable");
        assertConstants(VerifyReason.class, "", "not_found", "too_old", "tag_mismatch",
                "unidentifiable", "unscored", "already_consumed");
        assertConstants(WebhookEventType.class, "identification.completed",
                "identification.refused", "visitor.suspect", "billing.status_changed",
                "usage.daily_settled", "webhook.test");
        assertConstants(WebhookEnvironment.class, "live", "test");
        assertConstants(EventState.class, "pending", "enriched", "unavailable");
        assertConstants(Environment.class, "production", "staging", "development");
        assertConstants(Platform.class, "web", "android", "ios");
        assertConstants(AnonymityNetwork.class, "tor", "relay", "vpn", "proxy", "hosting");
        assertConstants(DeviceKind.class, "desktop", "mobile", "tablet", "server");
        assertConstants(VisitorKind.class, "human", "ai_bot", "automation", "search_crawler");
    }

    @Test
    void existingConstantsAreTheSameStrings() {
        assertEquals(Signal.ATTESTATION_FAILED, EventTrigger.SIGNAL_ATTESTATION_FAILED);
        assertEquals(Signal.BROWSER_TAMPERING, EventTrigger.SIGNAL_BROWSER_TAMPERING);
        assertEquals(Signal.SECONDARY_USER_PROFILE, EventTrigger.SIGNAL_SECONDARY_USER_PROFILE);
        assertEquals(VerifyReason.NOT_FOUND, VerifyResult.REASON_NOT_FOUND);
        assertEquals(VerifyReason.ALREADY_CONSUMED, VerifyResult.REASON_ALREADY_CONSUMED);
    }

    /** Compile-time constants, so they work as switch labels. */
    private static String route(String level) {
        switch (level) {
            case SuspectLevel.HIGH:
                return "review";
            case SuspectLevel.MEDIUM:
            case SuspectLevel.LOW:
                return "allow";
            default:
                return "unknown";
        }
    }

    @Test
    void unknownServerValuesStillDecode() throws IOException {
        String extra = "\"suspect_level\":\"severe\","
                + "\"suspect_score\":90,"
                + "\"anonymity_network\":\"mesh\","
                + "\"triggers\":[{\"signal\":\"a_signal_from_the_future\",\"weight\":9},"
                + "{\"signal\":\"fingerprint_suppressed\",\"weight\":4,\"confidence\":\"certain\"}],";
        String body = Fixtures.event(extra).replace("\"triggers\":[],", "");
        try (FakeApi api = FakeApi.json(200, body)) {
            Event event = FingerlyClient.builder().secretKey(SECRET_KEY).allowInsecureLoopback(true)
                    .endpoint(api.url()).build().events().get(REQUEST_ID);

            assertEquals("severe", event.getSuspectLevel());
            assertEquals("unknown", route(event.getSuspectLevel()));
            assertEquals("mesh", event.getAnonymityNetwork());
            assertEquals(EventState.ENRICHED, event.getState());
            assertEquals("a_signal_from_the_future", event.getTriggers().get(0).getSignal());
            assertEquals("certain", event.getTriggers().get(1).getConfidence());
            assertTrue(event.getTriggers().stream()
                    .anyMatch(t -> Signal.FINGERPRINT_SUPPRESSED.equals(t.getSignal())));
            assertFalse(SuspectLevel.HIGH.equals(event.getSuspectLevel()));
        }
    }

    @Test
    void unknownWebhookTypeStillParses() {
        byte[] body = ("{\"id\":\"evt\",\"type\":\"something.new\",\"version\":1,"
                + "\"organization_id\":\"org\",\"environment\":\"live\","
                + "\"created_at\":\"2026-09-16T09:41:12Z\",\"data\":{}}")
                .getBytes(StandardCharsets.UTF_8);

        WebhookEvent event = Webhooks.parse(body);

        assertEquals("something.new", event.getType());
        assertEquals(WebhookEnvironment.LIVE, event.getEnvironment());
        assertFalse(WebhookEventType.VISITOR_SUSPECT.equals(event.getType()));
    }
}
