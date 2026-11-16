package io.fingerly.server;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
            "\\Afly_(pk|sk|px)_(production|staging|development)_[A-Za-z0-9_-]+\\z");

    /** The one Fingerly API origin. */
    static final String DEFAULT_ENDPOINT = "https://api.fingerly.io";

    private static final Pattern API_VERSION = Pattern.compile("\\Av[1-9][0-9]*\\z");

    private static final Map<String, String> LABELS =
            Map.of(PUBLIC, "public", SECRET, "secret", PROXY, "proxy");

    private Keys() {}

    /** The environment a Fingerly key is scoped to. */
    static final class Scope {
        /** {@code production}, {@code staging} or {@code development}. */
        final String environment;

        Scope(String environment) {
            this.environment = environment;
        }
    }

    /**
     * Parses a Fingerly key and asserts it is of the expected kind.
     *
     * @param key the raw key, such as {@code fly_sk_production_…}
     * @param expected the kind the caller requires
     * @return the environment the key is scoped to
     * @throws IllegalArgumentException if the key is malformed or of another
     *     kind, so a public, proxy or management key can never reach an API
     *     that would accept it
     */
    static Scope parse(String key, String expected) {
        Matcher match = key == null ? null : KEY.matcher(key);
        if (match == null || !match.matches() || !match.group(1).equals(expected)) {
            throw new IllegalArgumentException(
                    "A valid Fingerly " + LABELS.get(expected) + " key is required");
        }
        return new Scope(match.group(2));
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

    /** Printable ASCII with no space: nothing a URL parser could tolerate and pass on. */
    private static final Pattern PRINTABLE = Pattern.compile("\\A[\\x21-\\x7e]+\\z");

    /** The only hosts a plain HTTP API origin may name, and only with the development flag. */
    private static final java.util.Set<String> LOOPBACK_HOSTS =
            java.util.Set.of("127.0.0.1", "localhost", "[::1]");

    /**
     * Validates an API origin option (the proxy's {@code upstream}): a bare
     * origin, nothing else.
     *
     * <p>Only scheme, host and an optional port are accepted. A path, query,
     * fragment, credentials, whitespace or control byte is refused, and the
     * returned origin is rebuilt from the parsed parts rather than the input.
     * The scheme is {@code https}; {@code http} is accepted only when
     * {@code allowInsecureLoopback} is set and the host is {@code 127.0.0.1},
     * {@code localhost} or {@code [::1]}. No message echoes the value.
     *
     * @param option the option's name, for the error message
     * @return the origin, as {@code scheme://host[:port]}
     * @throws IllegalArgumentException if the value is anything else
     */
    static String apiOrigin(String value, boolean allowInsecureLoopback, String option) {
        String bare = option + " must be an https origin (scheme, host and optional port only)";
        if (value == null || !PRINTABLE.matcher(value).matches()) {
            throw new IllegalArgumentException(bare);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception cause) {
            throw new IllegalArgumentException(bare);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        if (uri.isOpaque()
                || host == null
                || host.isEmpty()
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || !(path == null || path.isEmpty() || path.equals("/"))) {
            throw new IllegalArgumentException(bare);
        }
        if (scheme.equals("http")) {
            if (!allowInsecureLoopback || !LOOPBACK_HOSTS.contains(host)) {
                throw new IllegalArgumentException(
                        "a plain HTTP " + option
                                + " is allowed only for 127.0.0.1, localhost or [::1]"
                                + " with allowInsecureLoopback(true)");
            }
        } else if (!scheme.equals("https")) {
            throw new IllegalArgumentException(bare);
        }
        return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
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

    private static final Pattern IPV4 = Pattern.compile(
            "\\A(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
                    + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}\\z");

    private static final Pattern HEX_GROUP = Pattern.compile("\\A[0-9A-Fa-f]{1,4}\\z");

    /**
     * Reports whether a value is exactly one IPv4 or IPv6 address literal.
     *
     * <p>Parsed by hand rather than with {@code InetAddress}, which resolves
     * anything that is not a literal through DNS. Lists, ports, brackets,
     * zone indexes, and the shorthand IPv4 forms ({@code 127.1},
     * {@code 0x7f.0.0.1}, leading zeros) are all refused: none is a single
     * visitor address.
     */
    static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) {
            return false;
        }
        if (value.indexOf(':') < 0) {
            return IPV4.matcher(value).matches();
        }
        return isIpv6(value);
    }

    private static boolean isIpv6(String value) {
        int gap = value.indexOf("::");
        if (gap >= 0 && value.indexOf("::", gap + 1) >= 0) {
            return false; // "::" may appear once
        }
        String head = gap >= 0 ? value.substring(0, gap) : value;
        String tail = gap >= 0 ? value.substring(gap + 2) : "";
        List<String> groups = new ArrayList<>();
        if (!head.isEmpty()) {
            groups.addAll(Arrays.asList(head.split(":", -1)));
        }
        if (!tail.isEmpty()) {
            groups.addAll(Arrays.asList(tail.split(":", -1)));
        }
        int words = 0;
        for (int i = 0; i < groups.size(); i++) {
            String group = groups.get(i);
            // An embedded IPv4 address can only end the whole address.
            boolean last = i == groups.size() - 1 && (gap < 0 || !tail.isEmpty());
            if (last && group.indexOf('.') >= 0) {
                if (!IPV4.matcher(group).matches()) {
                    return false;
                }
                words += 2;
            } else if (HEX_GROUP.matcher(group).matches()) {
                words += 1;
            } else {
                return false;
            }
        }
        if (gap < 0) {
            return words == 8;
        }
        return words <= 7;
    }
}
