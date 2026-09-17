package io.fingerly.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Same-origin proxy for the browser SDK's identify and supplement calls.
 *
 * <p>Content blockers refuse browser requests to third-party APIs, and a
 * refused identification is a visitor never identified. Mounting this on a
 * path of your own site makes those requests first-party. The proxy key is
 * what lets that hop declare the visitor's real address, origin and user
 * agent; a public key alone never can, so nobody can spoof them.
 *
 * <p>This class holds every decision and speaks to no server API. Mount it
 * through one of the adapters, which only read a request and write a response:
 *
 * <ul>
 *   <li>{@code fingerly-server-jakarta} for Spring Boot 3, Tomcat 10+, Jetty 11+
 *   <li>{@code fingerly-server-javax} for Spring Boot 2 and older containers
 *   <li>{@code fingerly-server-webflux} for reactive Spring
 * </ul>
 *
 * <p>Only identify and supplement are forwarded, never event reads: reading
 * events needs a secret key and must stay unreachable from a browser. Customer
 * cookies and authorization headers are never passed on.
 */
public final class FingerlyProxy {

    private static final String DEFAULT_PREFIX = "/api/fingerly";
    private static final long DEFAULT_MAX_BODY_BYTES = 1L << 20; // 1 MiB
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT = "fingerly-java/" + FingerlyClient.VERSION;

    /** Statuses that must not carry a body. */
    private static final Set<Integer> BODILESS = Set.of(204, 205, 304);

    /**
     * Upstream headers that describe that connection rather than our response.
     * The HTTP client has already framed and decoded the body, so replaying
     * them would misdescribe the bytes handed back.
     */
    private static final Set<String> CONNECTION_HEADERS = Set.of(
            "connection", "content-encoding", "content-length", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade");

    /** Resolves the visitor's address from infrastructure your app trusts. */
    @FunctionalInterface
    public interface ClientIp {
        /**
         * Returns the visitor's address for one request.
         *
         * <p>Read it from a header your own infrastructure sets, such as your
         * load balancer's or CDN's. Never use the left-most
         * {@code X-Forwarded-For} value: the visitor writes it.
         */
        String resolve(ProxyRequest request);
    }

    private final String key;
    private final Keys.Scope scope;
    private final boolean usesProxyKey;
    private final String upstream;
    private final String prefix;
    private final String apiVersion;
    private final long maxBodyBytes;
    private final Duration timeout;
    private final ClientIp clientIp;
    private final HttpClient httpClient;

    private FingerlyProxy(Builder builder) {
        if (isBlank(builder.proxyKey) && isBlank(builder.secretKey)) {
            throw new IllegalArgumentException("A Fingerly proxy key is required");
        }
        if (!isBlank(builder.proxyKey) && !isBlank(builder.secretKey)) {
            throw new IllegalArgumentException("Pass proxyKey or deprecated secretKey, not both");
        }
        if (builder.clientIp == null) {
            throw new IllegalArgumentException("clientIp is required");
        }

        this.usesProxyKey = !isBlank(builder.proxyKey);
        this.key = usesProxyKey ? builder.proxyKey : builder.secretKey;
        this.scope = Keys.parse(key, usesProxyKey ? Keys.PROXY : Keys.SECRET);
        this.upstream = Keys.origin(
                builder.upstream != null ? builder.upstream : scope.origin());
        this.apiVersion = Keys.apiVersion(builder.apiVersion);
        this.prefix = normalizePrefix(builder.prefix);
        if (builder.maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        this.maxBodyBytes = builder.maxBodyBytes;
        this.timeout = Keys.positive(builder.timeout, "timeout");
        this.clientIp = builder.clientIp;
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** A new builder. {@code proxyKey} and {@code clientIp} are required. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reports whether a path is one this proxy serves.
     *
     * <p>Ask this before reading the body. An adapter that called
     * {@link #handle} to find out would have consumed the request body doing
     * it, and the application it then passed the request along to would
     * receive nothing.
     *
     * <p>The answer is about the path alone: a wrong method on a path Fingerly
     * owns is this proxy's 405 to give, not the application's 404.
     *
     * @param path the whole request path, including the mount prefix
     */
    public boolean handles(String path) {
        return path != null && routeFor(path) != null;
    }

    /** The request body limit, which adapters that buffer must also respect. */
    public long maxBodyBytes() {
        return maxBodyBytes;
    }

    /**
     * The refusal for a body that passed {@link #maxBodyBytes()}.
     *
     * <p>An adapter that enforces the limit itself, because its stack hands it
     * the body rather than a stream, answers with this so every standard
     * refuses oversized bodies identically.
     */
    public ProxyResponse payloadTooLarge() {
        return ProxyResponse.refusal(413, "Payload Too Large");
    }

    /**
     * Handles one browser request.
     *
     * <p>Never throws for a bad request: every refusal is a response, so an
     * adapter has nothing to translate but bytes.
     *
     * @param request the incoming request, as the adapter read it
     * @return the upstream response, or a 405, 404, 401, 413 or 502 refusal
     */
    public ProxyResponse handle(ProxyRequest request) {
        if (!"POST".equalsIgnoreCase(request.method())) {
            ProxyResponse refusal = ProxyResponse.refusal(405, "Method Not Allowed");
            List<Map.Entry<String, String>> headers = new ArrayList<>(refusal.headers());
            headers.add(Map.entry("Allow", "POST"));
            return new ProxyResponse(405, headers, refusal.body());
        }

        String upstreamPath = routeFor(request.path());
        if (upstreamPath == null) {
            return ProxyResponse.refusal(404, "Not Found");
        }

        String apiKey = upstreamKeyFor(request.header("X-API-Key"));
        if (apiKey == null) {
            return ProxyResponse.refusal(401, "Unauthorized");
        }

        // Refuse on the declared size first; the read below enforces the real one.
        if (request.contentLength() > maxBodyBytes) {
            return ProxyResponse.refusal(413, "Payload Too Large");
        }
        byte[] body;
        try {
            body = readBounded(request.body());
        } catch (BodyTooLargeException tooLarge) {
            return ProxyResponse.refusal(413, "Payload Too Large");
        } catch (IOException unreadable) {
            return ProxyResponse.refusal(400, "Bad Request");
        }

        HttpRequest.Builder upstreamRequest =
                HttpRequest.newBuilder(URI.create(upstream + upstreamPath))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .timeout(timeout);
        try {
            applyUpstreamHeaders(upstreamRequest, request, apiKey);
        } catch (IllegalArgumentException unusable) {
            // A clientIp that could inject a header is a programming error on
            // the customer's side, not something to forward.
            return ProxyResponse.refusal(500, "Internal Server Error");
        }

        try {
            HttpResponse<byte[]> response =
                    httpClient.send(upstreamRequest.build(), HttpResponse.BodyHandlers.ofByteArray());
            return relay(response);
        } catch (IOException unreachable) {
            return ProxyResponse.refusal(502, "Bad Gateway");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ProxyResponse.refusal(502, "Bad Gateway");
        }
    }

    /**
     * Maps a local request path to its upstream path.
     *
     * <p>The prefix is tolerated as either present or already stripped, so the
     * handler works whether the container reports the whole path or only what
     * followed the mount.
     *
     * @return the upstream path, or null when the route is not proxyable
     */
    private String routeFor(String path) {
        String versioned = "/api/" + apiVersion;
        String resource = null;
        if (path.startsWith(prefix + versioned)) {
            resource = path.substring((prefix + versioned).length());
        } else if (path.startsWith(versioned)) {
            resource = path.substring(versioned.length());
        }
        if (resource == null) {
            return null;
        }
        if (!resource.equals("/identify") && !isSupplement(resource)) {
            return null;
        }
        return versioned + resource;
    }

    /** True for {@code /events/{request_id}/supplement} with one id segment. */
    private static boolean isSupplement(String resource) {
        if (!resource.startsWith("/events/") || !resource.endsWith("/supplement")) {
            return false;
        }
        String requestId = resource.substring("/events/".length(),
                resource.length() - "/supplement".length());
        return !requestId.isEmpty() && !requestId.contains("/");
    }

    /**
     * Decides which key to present upstream.
     *
     * <p>With a proxy key the browser must supply a public key for the same
     * region and environment, which the proxy forwards alongside its own. The
     * deprecated secret-key mode authenticates on its own.
     *
     * @return the key to forward, or null when the caller is unauthorised
     */
    private String upstreamKeyFor(String presented) {
        if (!usesProxyKey) {
            return key;
        }
        Keys.Scope browser;
        try {
            browser = Keys.parse(presented, Keys.PUBLIC);
        } catch (IllegalArgumentException refused) {
            return null;
        }
        boolean sameDeployment = browser.region.equals(scope.region)
                && browser.environment.equals(scope.environment);
        return sameDeployment ? presented : null;
    }

    /**
     * Sets the upstream headers.
     *
     * <p>Nothing is copied wholesale: only the body's content type, the
     * visitor metadata Fingerly needs, and an idempotency key when the SDK
     * sent one.
     */
    private void applyUpstreamHeaders(
            HttpRequest.Builder upstream, ProxyRequest request, String apiKey) {
        String address = clientIp.resolve(request);
        if (!Keys.isHeaderSafe(address)) {
            throw new IllegalArgumentException("clientIp must resolve to a plain address");
        }

        String contentType = request.header("Content-Type");
        upstream.header("Content-Type", contentType == null ? "application/json" : contentType);
        upstream.header("X-API-Key", apiKey);
        upstream.header("X-Fingerly-Client-IP", address);
        upstream.header("X-Fingerly-Origin", orEmpty(request.header("Origin")));
        upstream.header("X-Fingerly-User-Agent", orEmpty(request.header("User-Agent")));
        upstream.header("User-Agent", USER_AGENT);
        if (usesProxyKey) {
            upstream.header("X-Fingerly-Proxy-Key", key);
        }
        String idempotency = request.header("Idempotency-Key");
        if (idempotency != null && !idempotency.isEmpty()) {
            upstream.header("Idempotency-Key", idempotency);
        }
    }

    /** Returns the upstream response, uncacheable and without its framing. */
    private static ProxyResponse relay(HttpResponse<byte[]> response) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        response.headers().map().forEach((name, values) -> {
            if (CONNECTION_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || "cache-control".equalsIgnoreCase(name)) {
                return;
            }
            values.forEach(value -> headers.add(Map.entry(name, value)));
        });
        headers.add(Map.entry("Cache-Control", "no-store"));

        byte[] body = BODILESS.contains(response.statusCode())
                ? new byte[0]
                : response.body();
        return new ProxyResponse(response.statusCode(), headers, body);
    }

    /**
     * Reads a body without buffering more than the limit.
     *
     * @throws BodyTooLargeException as soon as the limit is passed
     */
    private byte[] readBounded(InputStream stream) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(chunk)) != -1) {
            total += read;
            if (total > maxBodyBytes) {
                throw new BodyTooLargeException();
            }
            collected.write(chunk, 0, read);
        }
        return collected.toByteArray();
    }

    /** Raised internally when a streamed body passes the limit. */
    private static final class BodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static String normalizePrefix(String value) {
        String prefix = value == null ? DEFAULT_PREFIX : value.replaceAll("/+$", "");
        if (!prefix.startsWith("/") || prefix.contains("?") || prefix.contains("#")) {
            throw new IllegalArgumentException("prefix must be an absolute URL path");
        }
        return prefix;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Builds a {@link FingerlyProxy}. */
    public static final class Builder {
        private String proxyKey;
        private String secretKey;
        private String prefix = DEFAULT_PREFIX;
        private String apiVersion = "v1";
        private String upstream;
        private long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        private Duration timeout = DEFAULT_TIMEOUT;
        private ClientIp clientIp;
        private HttpClient httpClient;

        private Builder() {}

        /** A proxy key, {@code fly_px_…}, held only on your server. Required. */
        public Builder proxyKey(String proxyKey) {
            this.proxyKey = proxyKey;
            return this;
        }

        /**
         * The deprecated alternative to {@link #proxyKey}. Secret read keys
         * will be refused in the next major version; do not pass both.
         *
         * @deprecated use {@link #proxyKey}
         */
        @Deprecated
        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        /**
         * Resolves the visitor's address from infrastructure you trust.
         * Required.
         */
        public Builder clientIp(ClientIp clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        /**
         * The path the proxy is mounted under, which it must match. Defaults
         * to {@code /api/fingerly}.
         */
        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /** The version accepted locally and forwarded upstream. Defaults to {@code v1}. */
        public Builder apiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        /** Overrides the API origin implied by the key. */
        public Builder upstream(String upstream) {
            this.upstream = upstream;
            return this;
        }

        /** Larger request bodies are refused with 413. Defaults to 1 MiB. */
        public Builder maxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        /** How long to wait for Fingerly. Defaults to 5 seconds. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Supplies the HTTP client for the upstream call. Configure it with
         * {@link HttpClient.Redirect#NEVER}.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Builds the proxy, validating every option first.
         *
         * @throws IllegalArgumentException if a credential, route, limit or
         *     callback is invalid
         */
        public FingerlyProxy build() {
            return new FingerlyProxy(this);
        }
    }
}
