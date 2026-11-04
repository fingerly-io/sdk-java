package io.fingerly.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Same-origin proxy for the browser SDK.
 *
 * <p>Content blockers refuse browser requests to third-party APIs, and a
 * refused identification is a visitor never identified. Mounting this on the
 * path the dashboard issued, such as {@code /k7x2qm4a}, makes those requests
 * first-party. The SDK posts both of its calls to that one path, and this
 * forwards each to Fingerly's one ingest URL. The proxy key is what lets that
 * hop declare the visitor's real address, origin and user agent; a public key
 * alone never can, so nobody can spoof them.
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
 * <p>Nothing but the ingest URL is reachable through it, never event reads:
 * reading events needs a secret key and must stay unreachable from a browser.
 * Customer cookies and authorization headers are never passed on, and nothing
 * relayed back names Fingerly.
 */
public final class FingerlyProxy {

    private static final long DEFAULT_MAX_BODY_BYTES = 1L << 20; // 1 MiB
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT = "fingerly-java/" + FingerlyClient.VERSION;

    /** What this proxy reports itself as upstream; shown per proxy in the dashboard. */
    private static final String PROXY_VERSION = "java/" + FingerlyClient.VERSION;

    /** Statuses that must not carry a body. */
    private static final Set<Integer> BODILESS = Set.of(204, 205, 304);

    /**
     * The response headers relayed to the browser, and nothing else. A content
     * blocker can match a response header by name, so an allowlist is what
     * keeps anything naming Fingerly off the page, whatever the API adds in
     * future. It also leaves out the framing headers, which would misdescribe
     * a body the HTTP client has already decoded.
     */
    private static final List<String> RELAYED_HEADERS = List.of(
            "Content-Type", "X-Request-Id", "RateLimit-Limit", "RateLimit-Remaining",
            "Retry-After");

    /** Where failure lines go when the builder was given no {@code log}. */
    private static final System.Logger LOGGER = System.getLogger(FingerlyProxy.class.getName());

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
    private final String upstreamUrl;
    private final String path;
    private final long maxBodyBytes;
    private final Duration timeout;
    private final ClientIp clientIp;
    private final HttpClient httpClient;
    private final Consumer<String> log;

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
        this.upstreamUrl = Keys.origin(
                builder.upstream != null ? builder.upstream : Keys.DEFAULT_ENDPOINT)
                + "/api/" + Keys.apiVersion(builder.apiVersion) + "/ingest";
        this.path = builder.path == null ? null : normalizePath(builder.path);
        if (builder.maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        this.maxBodyBytes = builder.maxBodyBytes;
        this.timeout = Keys.positive(builder.timeout, "timeout");
        this.clientIp = builder.clientIp;
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.log = builder.log != null
                ? builder.log
                : line -> LOGGER.log(System.Logger.Level.WARNING, line);
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
     * <p>The answer is about the path alone: a wrong method on the proxy's
     * path is this proxy's 405 to give, not the application's 404. Without a
     * configured {@link Builder#path path} every path is served, because the
     * framework already chose which requests reach the proxy.
     *
     * @param path the whole request path, including any context path
     */
    public boolean handles(String path) {
        if (this.path == null) {
            return true;
        }
        return path != null && path.replaceAll("/+$", "").equals(this.path);
    }

    /**
     * The one path this proxy answers, without trailing slashes, or empty when
     * it answers wherever the framework routes it.
     */
    public Optional<String> path() {
        return Optional.ofNullable(path);
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
     * @return the upstream response, or a 404, 405, 401, 413, 500, 502 or 504 refusal
     */
    public ProxyResponse handle(ProxyRequest request) {
        if (!handles(request.path())) {
            return ProxyResponse.refusal(404, "Not Found");
        }
        if (!"POST".equalsIgnoreCase(request.method())) {
            ProxyResponse refusal = ProxyResponse.refusal(405, "Method Not Allowed");
            List<Map.Entry<String, String>> headers = new ArrayList<>(refusal.headers());
            headers.add(Map.entry("Allow", "POST"));
            return new ProxyResponse(405, headers, refusal.body());
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
                HttpRequest.newBuilder(URI.create(upstreamUrl))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .timeout(timeout);
        try {
            applyUpstreamHeaders(upstreamRequest, request, apiKey);
        } catch (IllegalArgumentException unusable) {
            // A clientIp that could inject a header is a programming error on
            // the customer's side, not something to forward.
            return ProxyResponse.refusal(500, "Internal Server Error");
        }

        return forward(upstreamRequest.build());
    }

    /**
     * Sends the request upstream and relays the answer: 504 when there is
     * none in time, 502 for any other upstream failure.
     *
     * <p>The deadline covers receiving the whole body, which is why this
     * waits on a future rather than calling {@code HttpClient.send}: the
     * timeout on an {@link HttpRequest} elapses only until the response
     * headers arrive, so an upstream that sent headers and then stalled could
     * hold the browser's request, and a server thread, open indefinitely.
     * Waiting with a timeout bounds the whole exchange, and cancelling on
     * expiry releases the connection instead of leaving it to finish unread.
     *
     * <p>The body is read whole, within {@link BoundedBody#LIMIT}, before
     * anything is relayed, so a truncated answer is never passed on as a
     * success.
     */
    private ProxyResponse forward(HttpRequest request) {
        BoundedBody body = new BoundedBody();
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = httpClient.sendAsync(request, body);
        } catch (RuntimeException refused) {
            return failed(refused);
        }

        try {
            return relay(pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException expired) {
            pending.cancel(true);
            body.cancel();
            return failed(expired);
        } catch (ExecutionException failure) {
            return failed(failure.getCause());
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            body.cancel();
            Thread.currentThread().interrupt();
            return failed(interrupted);
        }
    }

    /**
     * Answers an upstream failure, logging one line for it.
     *
     * <p>A timeout of either kind is the browser's 504; anything else the
     * upstream did (refused, reset, unresolvable, truncated, oversized, or
     * compressed in a way that cannot be decoded) is its 502. Neither body
     * names Fingerly.
     */
    private ProxyResponse failed(Throwable cause) {
        Throwable root = cause instanceof CompletionException && cause.getCause() != null
                ? cause.getCause()
                : cause;
        boolean timedOut = root instanceof TimeoutException || root instanceof HttpTimeoutException;
        try {
            log.accept(timedOut
                    ? "Fingerly proxy: upstream did not answer within " + timeout.toMillis() + " ms"
                    : "Fingerly proxy: upstream failed (" + describe(root) + ")");
        } catch (RuntimeException ignored) {
            // A logging hook that throws must not turn a 502 into a 500.
        }
        return timedOut
                ? ProxyResponse.refusal(504, "Gateway timeout")
                : ProxyResponse.refusal(502, "Bad gateway");
    }

    /** One short, key-free description of a failure: its type and nothing it carries. */
    private static String describe(Throwable cause) {
        if (cause instanceof BoundedBody.TooLargeException) {
            return "response larger than " + BoundedBody.LIMIT + " bytes";
        }
        if (cause instanceof BoundedBody.UnsupportedEncodingException) {
            return "unsupported content coding";
        }
        return cause == null ? "unknown" : cause.getClass().getSimpleName();
    }

    /**
     * Decides which key to present upstream.
     *
     * <p>With a proxy key the browser must supply a public key for the same
     * environment, which the proxy forwards alongside its own. The deprecated
     * secret-key mode authenticates on its own.
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
        return browser.environment.equals(scope.environment) ? presented : null;
    }

    /**
     * Sets the upstream headers.
     *
     * <p>Nothing is copied wholesale: only the body's content type, the
     * visitor metadata Fingerly needs, this proxy's version, and an
     * idempotency key when the SDK sent one.
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
        upstream.header("X-Fingerly-Proxy-Version", PROXY_VERSION);
        if (usesProxyKey) {
            upstream.header("X-Fingerly-Proxy-Key", key);
        }
        String idempotency = request.header("Idempotency-Key");
        if (idempotency != null && !idempotency.isEmpty()) {
            upstream.header("Idempotency-Key", idempotency);
        }
    }

    /**
     * Returns the upstream response, uncacheable and with only the allowlisted
     * headers. A value naming Fingerly is dropped too, since a blocker can
     * match that as well as a name.
     */
    private static ProxyResponse relay(HttpResponse<byte[]> response) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (String name : RELAYED_HEADERS) {
            for (String value : response.headers().allValues(name)) {
                if (!value.toLowerCase(Locale.ROOT).contains("fingerly")) {
                    headers.add(Map.entry(name, value));
                }
            }
        }
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

    /**
     * Validates the path the dashboard issued and drops trailing slashes. It
     * is matched exactly: the proxy answers that path and nothing under it.
     */
    private static String normalizePath(String value) {
        String path = value.trim().replaceAll("/+$", "");
        if (!path.startsWith("/") || path.length() < 2
                || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException(
                    "path must be an absolute URL path such as /k7x2qm4a");
        }
        return path;
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
        private String path;
        private String apiVersion = "v1";
        private String upstream;
        private long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        private Duration timeout = DEFAULT_TIMEOUT;
        private ClientIp clientIp;
        private HttpClient httpClient;
        private Consumer<String> log;

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
         * The path the dashboard issued for this proxy, such as
         * {@code /k7x2qm4a}, matched exactly against the whole request path;
         * any other path gets 404, or passes through to the application under
         * a filter. Omit it when the framework already routes only that path
         * to the proxy.
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /** The API version forwarded upstream. Defaults to {@code v1}. */
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

        /**
         * How long to wait for Fingerly, covering the whole response body;
         * past it the browser gets 502. Defaults to 5 seconds.
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Receives one line per upstream failure, without stack traces or
         * credentials. Defaults to the {@code System.Logger} named after this
         * class at {@code WARNING}, which reaches SLF4J, Logback or Log4j
         * through their usual bridges.
         */
        public Builder log(Consumer<String> log) {
            this.log = log;
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
         * @throws IllegalArgumentException if a credential, path, limit or
         *     callback is invalid
         */
        public FingerlyProxy build() {
            return new FingerlyProxy(this);
        }
    }
}
