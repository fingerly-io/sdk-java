package io.fingerly.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signature verification.
 *
 * <p>Stateless and static: verifying a delivery is one HMAC over bytes you
 * already have, so it needs no client.
 */
public final class Webhooks {

    /** How far a delivery's timestamp may be from now before it is a replay. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private static final String PREFIX = "sha256=";

    /**
     * A lowercase hex digest, exactly as Fingerly sends it. Anchored with
     * {@code \A} and {@code \z} because Java's {@code $} also matches before a
     * trailing newline.
     */
    private static final Pattern SIGNATURE = Pattern.compile("\\Asha256=[a-f0-9]{64}\\z");

    /** Whole unsigned seconds, and nothing else. */
    private static final Pattern TIMESTAMP = Pattern.compile("\\A[0-9]+\\z");

    private Webhooks() {}

    /**
     * Verifies a delivery against the raw request body, with the default
     * five-minute replay window.
     *
     * <pre>{@code
     * if (!Webhooks.verify(secret, body, timestamp, signature)) {
     *     return ResponseEntity.badRequest().build();
     * }
     * }</pre>
     *
     * @param secret the endpoint's signing secret
     * @param payload the exact bytes received, before any JSON parsing:
     *     re-serialising them changes the digest
     * @param timestamp the {@code x-fingerly-timestamp} header
     * @param signature the {@code x-fingerly-signature} header, which may
     *     carry several comma-separated signatures during a secret rotation
     * @return true when a signature matches and the timestamp is recent.
     *     Malformed or missing input returns false rather than throwing.
     */
    public static boolean verify(String secret, byte[] payload, String timestamp, String signature) {
        return verify(secret, payload, timestamp, signature, DEFAULT_TOLERANCE, null);
    }

    /**
     * Verifies a delivery with an explicit replay window.
     *
     * @param tolerance how far the timestamp may be from now
     * @see #verify(String, byte[], String, String)
     */
    public static boolean verify(
            String secret, byte[] payload, String timestamp, String signature, Duration tolerance) {
        return verify(secret, payload, timestamp, signature, tolerance, null);
    }

    /**
     * Verifies a delivery at a fixed instant. Override {@code now} only in
     * tests.
     *
     * @see #verify(String, byte[], String, String)
     */
    public static boolean verify(
            String secret,
            byte[] payload,
            String timestamp,
            String signature,
            Duration tolerance,
            Instant now) {
        if (secret == null || secret.isEmpty() || timestamp == null || signature == null) {
            return false;
        }
        if (payload == null) {
            return false;
        }
        // Check freshness before spending an HMAC on an obvious replay.
        if (!isFresh(timestamp, now, tolerance)) {
            return false;
        }

        byte[] expected = sign(secret, timestamp, payload);
        for (String candidate : signature.split(",")) {
            if (matches(candidate, expected)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a delivery's envelope.
     *
     * <p>Verify the bytes before calling this: parsing an unverified body is
     * trusting whoever sent it.
     *
     * @throws FingerlyException if the body is not a Fingerly webhook envelope
     */
    public static WebhookEvent parse(byte[] payload) {
        return Json.convert(Json.tree(payload), WebhookEvent.class);
    }

    /** Reports whether the signed time is within tolerance of now. */
    private static boolean isFresh(String timestamp, Instant now, Duration tolerance) {
        if (!TIMESTAMP.matcher(timestamp).matches()
                || tolerance == null
                || tolerance.isNegative()) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException ignored) {
            return false;
        }
        Instant moment = now == null ? Instant.now() : now;
        long drift = Math.abs(moment.getEpochSecond() - seconds);
        return drift <= tolerance.getSeconds();
    }

    /**
     * Computes the expected digest.
     *
     * <p>Fingerly signs the timestamp, a literal dot, then the raw body bytes.
     *
     * @return the hex digest as ASCII bytes, ready for a constant-time compare
     */
    private static byte[] sign(String secret, String timestamp, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(payload);
            return hex(mac.doFinal()).getBytes(StandardCharsets.US_ASCII);
        } catch (java.security.GeneralSecurityException cause) {
            // HmacSHA256 is required of every Java platform; its absence is
            // not something a caller can handle.
            throw new IllegalStateException("HmacSHA256 is unavailable", cause);
        }
    }

    /** Compares one candidate signature in constant time. */
    private static boolean matches(String candidate, byte[] expected) {
        String value = candidate.trim();
        if (!SIGNATURE.matcher(value).matches()) {
            return false;
        }
        byte[] actual = value.substring(PREFIX.length()).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }
}
