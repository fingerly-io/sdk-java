package io.fingerly.server;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation of caller-supplied configuration.
 *
 * <p>Everything here runs before any network access, so a misconfigured client
 * fails at construction rather than mid-request or against the wrong host.
 */
final class Keys {

    /** Browser public key. */
    static final String PUBLIC = "pk";
    /** Server-only secret key. */
    static final String SECRET = "sk";
    /** Write-only proxy key. */
    static final String PROXY = "px";

    /**
     * Anchored with {@code \A} and {@code \z} rather than {@code ^} and
     * {@code $}, because Java's {@code $} also matches before a trailing
     * newline.
     */
    private static final Pattern KEY = Pattern.compile(
            "\\Afly_(pk|sk|px)_(eu|us)_(production|staging|development)_[A-Za-z0-9_-]+\\z");

    private static final Pattern API_VERSION = Pattern.compile("\\Av[1-9][0-9]*\\z");

    private static final Map<String, String> LABELS =
            Map.of(PUBLIC, "public", SECRET, "secret", PROXY, "proxy");

    private Keys() {}

    /** The region and environment a Fingerly key is scoped to. */
    static final class Scope {
        /** {@code eu} or {@code us}; selects the regional API host. */
        final String region;
        /** {@code production}, {@code staging} or {@code development}. */
        final String environment;

        Scope(String region, String environment) {
            this.region = region;
            this.environment = environment;
        }

        /** The Fingerly API origin serving this scope's region. */
        String origin() {
            return "https://" + region + ".api.fingerly.io";
        }
    }

    /**
     * Parses a Fingerly key and asserts it is of the expected kind.
     *
     * @param key the raw key, such as {@code fly_sk_us_production_…}
     * @param expected the kind the caller requires
     * @return the region and environment the key is scoped to
     * @throws IllegalArgumentException if the key is malformed or of another
     *     kind, so a public, proxy or management key can never reach an API
     *     that would accept it
     */
    static Scope parse(String key, String expected) {
        Matcher match = key == null ? null : KEY.matcher(key);
        if (match == null || !match.matches() || !match.group(1).equals(expected)) {
            throw new IllegalArgumentException(
                    "A valid regional Fingerly " + LABELS.get(expected) + " key is required");
        }
        return new Scope(match.group(2), match.group(3));
    }

    /**
     * Normalises a caller-supplied API origin.
     *
     * <p>Credentials, queries and fragments are refused: they would be
     * attached to every authenticated request, and embedded credentials would
     * leak on a redirect.
     *
     * @return the origin without trailing slashes
     * @throws IllegalArgumentException if the URL is malformed or carries any
     *     of the above
     */
    static String origin(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception cause) {
            throw new IllegalArgumentException("endpoint is not a URL", cause);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https"))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "endpoint must be an HTTP(S) URL without credentials, query, or fragment");
        }
        return value.replaceAll("/+$", "");
    }

    /**
     * Validates an API route version before it is spliced into a URL.
     *
     * @throws IllegalArgumentException if the version is not of that shape
     */
    static String apiVersion(String version) {
        if (version == null || !API_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("apiVersion must be a version such as \"v1\" or \"v2\"");
        }
        return version;
    }

    /**
     * Validates a duration option.
     *
     * @throws IllegalArgumentException if it is null, zero or negative
     */
    static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
        return value;
    }

    /** Reports whether a value is printable ASCII, so it cannot inject a header. */
    static boolean isHeaderSafe(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }
}
