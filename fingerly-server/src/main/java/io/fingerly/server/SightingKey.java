package io.fingerly.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The agent sightings cache key, exactly as the contract defines it, shared
 * by every server SDK so their answers are remembered alike.
 *
 * <pre>
 * key = lowercase hex of the first 16 bytes of
 *       SHA-256(user_agent 0x00 canonical_ip 0x00 signature_agent 0x00 signed)
 * </pre>
 */
final class SightingKey {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SightingKey() {}

    /**
     * The key for one request.
     *
     * @param userAgent the {@code User-Agent} header, or empty
     * @param canonicalIp the address from {@link #canonicalIp}
     * @param signatureAgent the {@code Signature-Agent} header, or empty
     * @param signed whether both {@code Signature} and {@code Signature-Input} were present
     */
    static String of(String userAgent, String canonicalIp, String signatureAgent, boolean signed) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java platform is required to ship SHA-256.
            throw new IllegalStateException(impossible);
        }
        sha256.update(userAgent.getBytes(StandardCharsets.UTF_8));
        sha256.update((byte) 0);
        sha256.update(canonicalIp.getBytes(StandardCharsets.UTF_8));
        sha256.update((byte) 0);
        sha256.update(signatureAgent.getBytes(StandardCharsets.UTF_8));
        sha256.update((byte) 0);
        sha256.update((byte) (signed ? '1' : '0'));
        byte[] digest = sha256.digest();
        char[] hex = new char[32];
        for (int i = 0; i < 16; i++) {
            hex[2 * i] = HEX[(digest[i] >> 4) & 0x0f];
            hex[2 * i + 1] = HEX[digest[i] & 0x0f];
        }
        return new String(hex);
    }

    /**
     * One IP literal in its canonical text form: an IPv4-mapped IPv6 address
     * as the IPv4 address, and IPv6 as RFC 5952 prints it (lowercase, the
     * longest run of zero groups compressed).
     *
     * @return the canonical form, or null when the value is not exactly one
     *     IPv4 or IPv6 literal (a list, a port, a zone, a host name)
     */
    static String canonicalIp(String value) {
        // Validated first, so the parse below never turns into a DNS lookup.
        if (!Keys.isIpLiteral(value)) {
            return null;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(value);
        } catch (UnknownHostException unreadable) {
            return null;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return ipv4(bytes, 0);
        }
        if (isMapped(bytes)) {
            return ipv4(bytes, 12);
        }
        return ipv6(bytes);
    }

    private static boolean isMapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static String ipv4(byte[] bytes, int from) {
        return (bytes[from] & 0xff) + "." + (bytes[from + 1] & 0xff) + "."
                + (bytes[from + 2] & 0xff) + "." + (bytes[from + 3] & 0xff);
    }

    private static String ipv6(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[2 * i] & 0xff) << 8) | (bytes[2 * i + 1] & 0xff);
        }
        // RFC 5952 4.2: compress the longest run of two or more zero groups,
        // the first one when runs tie.
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < 8 && groups[i] == 0) {
                i++;
            }
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        StringBuilder out = new StringBuilder(39);
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        return out.toString();
    }
}
