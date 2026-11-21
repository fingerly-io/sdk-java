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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * First-party proxy for Fingerly Web and native client SDKs.
 *
 * <p>Content blockers refuse browser requests to third-party APIs, and a
 * refused identification is a visitor never identified. Mounting this on the
 * path the dashboard issued, such as {@code /k7x2qm4a}, makes those requests
 * first-party. Client SDKs post single-pass {@code v: 3} envelopes to that one
 * path, and this forwards raw request bytes untouched to Fingerly's one ingest
 * URL. The proxy key is what lets that hop declare the visitor's real address,
 * origin and user agent; a public key alone never can, so nobody can spoof them.
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

    /** Allowlisted caller headers forwarded verbatim when present and safe. */
    private static final List<String> FORWARDED_CLIENT_HEADERS = List.of(
            "Idempotency-Key",
            "Sec-Fetch-Site",
            "Sec-Fetch-Mode",
            "Sec-Fetch-Dest",
            "Sec-CH-UA",
            "Sec-CH-UA-Mobile",
            "Sec-CH-UA-Platform");

    /**
     * Web Bot Auth headers an AI agent's browser signs the request with,
     * forwarded when present, safe and at most
     * {@link #MAX_SIGNATURE_HEADER_BYTES}. The API ignores a longer one, so it
     * is dropped here rather than refused.
     */
    private static final List<String> SIGNATURE_HEADERS = List.of(
            "Signature",
            "Signature-Input",
            "Signature-Agent");

    /** The longest Web Bot Auth header value forwarded, in bytes. */
    private static final int MAX_SIGNATURE_HEADER_BYTES = 4096;

    /** Exact native/bridge SDK platform identifiers restored after stripping x-fingerly-*. */
    private static final Pattern ALLOWED_PLATFORMS = Pattern.compile(
            "\\A(ios|android|react-native|react-native-ios|react-native-android"
                    + "|flutter|flutter-ios|flutter-android|lynx-ios|lynx-android"
                    + "|capacitor|kotlin-multiplatform)\\z");

    /** Refusal payload when both Origin and a validated native platform header are absent. */
    static final String MISSING_ORIGIN_JSON =
            "{\"error\":{\"code\":\"missing_origin\",\"message\":\"Missing origin\",\"status\":400}}";

    /** The 500 body, and log line, for a {@link ClientIp} that returned no address. */
    static final String NO_CLIENT_IP = "The client IP callback returned no valid address";

    /** Set once the misconfiguration above has been logged in this process. */
    private static final AtomicBoolean WARNED_NO_CLIENT_IP = new AtomicBoolean();

    /** Where failure lines go when the builder was given no {@code log}. */
    private static final System.Logger LOGGER = System.getLogger(FingerlyProxy.class.getName());

    /** Resolves the visitor's address from infrastructure your app trusts. */
    @FunctionalInterface
    public interface ClientIp {
        /**
         * Returns the visitor's address for one request: exactly one IPv4 or
         * IPv6 literal. Anything else refuses the request with a 500.
         *
         * <p>Behind a CDN or load balancer, read the header it sets, and
         * trust that header only when traffic can reach you through nothing
         * else. Without one, return {@link ProxyRequest#remoteAddress()}.
         * Never use the left-most {@code X-Forwarded-For} value: the visitor
         * writes it.
         */
        String resolve(ProxyRequest request);
    }

    private final String key;
    private final Keys.Scope scope;
    private final String upstreamUrl;
    private final String path;
    private final Set<String> allowedOrigins;
    private final long maxBodyBytes;
    private final Duration timeout;
    private final ClientIp clientIp;
    private final HttpClient httpClient;
    private final Consumer<String> log;

    private FingerlyProxy(Builder builder) {
        if (isBlank(builder.proxyKey)) {
            throw new IllegalArgumentException("A Fingerly proxy key is required");
        }
        if (builder.clientIp == null) {
            throw new IllegalArgumentException("clientIp is required");
        }

        this.key = builder.proxyKey;
        this.scope = Keys.parse(key, Keys.PROXY);
        this.upstreamUrl = Keys.apiOrigin(
                builder.upstream != null ? builder.upstream : Keys.DEFAULT_ENDPOINT,
                builder.allowInsecureLoopback, "upstream")
                + "/api/" + Keys.apiVersion(builder.apiVersion) + "/ingest";
        // The proxy key rides on every upstream call, so a client that follows
        // redirects would hand it to whatever host a redirect names.
        if (builder.httpClient != null
                && builder.httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("httpClient must be built with Redirect.NEVER");
        }
        this.path = builder.path == null ? null : normalizePath(builder.path);
        this.allowedOrigins = builder.allowedOrigins;
        if (builder.maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        this.maxBodyBytes = builder.maxBodyBytes;
        this.timeout = Keys.positive(builder.timeout, "timeout");
        this.clientIp = builder.clientIp;
        // Null means the process-wide pool, picked per request so it can be
        // recycled for DNS.
        this.httpClient = builder.httpClient;
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
     * <p>The match is exact and case-sensitive, against the raw path as it
     * arrived: no trailing or doubled slash, no dot segment, no percent
     * encoding and no path parameter matches, since each is a different URL
     * that some layer in front of the application may resolve differently.
     *
     * @param path the whole raw request path, including any context path
     */
    public boolean handles(String path) {
        if (this.path == null) {
            return true;
        }
        return this.path.equals(path);
    }

    /**
     * The one path this proxy answers, or empty when it answers wherever the
     * framework routes it.
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
     * Handles one browser request, waiting on the calling thread.
     *
     * <p>Never throws: every refusal and every upstream failure is a
     * response, so an adapter has nothing to translate but bytes. Prefer
     * {@link #handleAsync} wherever the framework can finish a request later:
     * this holds the calling thread for as long as the upstream takes, up to
     * the timeout.
     *
     * @param request the incoming request, as the adapter read it
     * @return the upstream response, or a 404, 405, 401, 413, 500, 502 or 504 refusal
     */
    public ProxyResponse handle(ProxyRequest request) {
        CompletableFuture<ProxyResponse> pending = handleAsync(request);
        try {
            return pending.get();
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return ProxyResponse.refusal(502, "Bad gateway", corsHeaders(corsOriginFor(request)));
        } catch (ExecutionException | java.util.concurrent.CancellationException unexpected) {
            return failed(unexpected, corsOriginFor(request));
        }
    }

    /**
     * Handles one browser request without holding a thread while the
     * upstream answers.
     *
     * <p>The request body is read on the calling thread, within
     * {@link #maxBodyBytes()}; the upstream call then runs on the HTTP
     * client's own threads. The future always completes normally, with the
     * upstream response or a refusal, and within the timeout. Cancelling it,
     * as an adapter does when the visitor disconnects, abandons the upstream
     * call and closes its connection.
     *
     * @param request the incoming request, as the adapter read it
     * @return the upstream response, or a 404, 405, 401, 400, 413, 500, 502 or 504 refusal
     */
    public CompletableFuture<ProxyResponse> handleAsync(ProxyRequest request) {
        String corsOrigin = corsOriginFor(request);
        try {
            Object prepared = prepare(request, corsOrigin);
            return prepared instanceof ProxyResponse
                    ? CompletableFuture.completedFuture((ProxyResponse) prepared)
                    : forward((HttpRequest) prepared, corsOrigin);
        } catch (RuntimeException unexpected) {
            return CompletableFuture.completedFuture(failed(unexpected, corsOrigin));
        }
    }

    /**
     * Checks the request and builds the upstream call.
     *
     * @return the refusal to answer with, or the {@link HttpRequest} to send
     */
    private Object prepare(ProxyRequest request, String corsOrigin) {
        if (!handles(request.path())) {
            return ProxyResponse.refusal(404, "Not Found");
        }

        // Step 1: Capture validated x-fingerly-sdk-platform before stripping x-fingerly-*.
        String rawPlatform = request.header("X-Fingerly-SDK-Platform");
        String sdkPlatform = rawPlatform != null && ALLOWED_PLATFORMS.matcher(rawPlatform).matches()
                ? rawPlatform
                : null;
        String origin = nonBlank(safe(request.header("Origin")));

        if (!allowedOrigins.isEmpty() && "OPTIONS".equals(request.method())) {
            if (origin != null && corsOrigin == null) {
                return ProxyResponse.refusal(403, "Forbidden");
            }
            List<Map.Entry<String, String>> headers = new ArrayList<>();
            headers.add(Map.entry("Cache-Control", "no-store"));
            headers.add(Map.entry("Allow", "POST, OPTIONS"));
            headers.addAll(corsHeaders(corsOrigin));
            return new ProxyResponse(204, headers, new byte[0]);
        }

        // Methods are case-sensitive: "post" is a different method, not POST.
        if (!"POST".equals(request.method())) {
            List<Map.Entry<String, String>> extra = new ArrayList<>();
            extra.add(Map.entry("Allow", allowedOrigins.isEmpty() ? "POST" : "POST, OPTIONS"));
            extra.addAll(corsHeaders(corsOrigin));
            return ProxyResponse.refusal(405, "Method Not Allowed", extra);
        }

        String apiKey = upstreamKeyFor(request.header("X-API-Key"));
        if (apiKey == null) {
            return ProxyResponse.refusal(401, "Unauthorized", corsHeaders(corsOrigin));
        }

        // Step 4: Enforce Origin / native-platform gate (never synthesize X-Fingerly-Origin).
        if (origin == null && sdkPlatform == null) {
            return ProxyResponse.jsonRefusal(400, MISSING_ORIGIN_JSON, corsHeaders(corsOrigin));
        }

        // Refuse on the declared size first; the read below enforces the real one.
        if (request.contentLength() > maxBodyBytes) {
            return ProxyResponse.refusal(413, "Payload Too Large", corsHeaders(corsOrigin));
        }

        String address = visitorAddress(request);
        if (address == null) {
            return ProxyResponse.refusal(500, NO_CLIENT_IP, corsHeaders(corsOrigin));
        }

        byte[] body;
        try {
            body = readBounded(request.body());
        } catch (BodyTooLargeException tooLarge) {
            return ProxyResponse.refusal(413, "Payload Too Large", corsHeaders(corsOrigin));
        } catch (IOException unreadable) {
            return ProxyResponse.refusal(400, "Bad Request", corsHeaders(corsOrigin));
        }

        HttpRequest.Builder upstreamRequest =
                HttpRequest.newBuilder(URI.create(upstreamUrl))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .timeout(timeout);
        applyUpstreamHeaders(upstreamRequest, request, apiKey, address, origin, sdkPlatform);
        return upstreamRequest.build();
    }

    /**
     * Sends the request upstream and relays the answer: 504 when there is
     * none in time, 502 for any other upstream failure.
     *
     * <p>One deadline covers the whole exchange, receiving the body
     * included. The timeout on an {@link HttpRequest} elapses only until the
     * response headers arrive, so an upstream that sent headers and then
     * trickled could otherwise hold the browser's request open indefinitely.
     * On expiry the call is abandoned and its connection closed rather than
     * left to finish unread.
     *
     * <p>The body is read whole, within {@link BoundedBody#LIMIT}, before
     * anything is relayed, so a truncated answer is never passed on as a
     * success.
     */
    private CompletableFuture<ProxyResponse> forward(HttpRequest request, String corsOrigin) {
        BoundedBody body = new BoundedBody();
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = client().sendAsync(request, body);
        } catch (RuntimeException refused) {
            return CompletableFuture.completedFuture(failed(refused, corsOrigin));
        }

        CompletableFuture<ProxyResponse> result = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean settled =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ScheduledFuture<?> deadline = Deadlines.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                pending.cancel(true);
                body.cancel();
                result.complete(failed(new TimeoutException(), corsOrigin));
            }
        }, timeout);
        pending.whenComplete((response, failure) -> {
            deadline.cancel(false);
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            if (failure != null) {
                result.complete(failed(failure, corsOrigin));
                return;
            }
            int status = response.statusCode();
            if (status < 200 || status > 599 || (status >= 300 && status <= 399)) {
                // A redirect is never followed or passed on, and a status
                // outside 200 to 599 is not an answer a browser can use.
                result.complete(failed(new UnusableStatusException(status), corsOrigin));
                return;
            }
            try {
                result.complete(relay(response, corsOrigin));
            } catch (RuntimeException unexpected) {
                result.complete(failed(unexpected, corsOrigin));
            }
        });
        // A cancelled result is a visitor who left: drop the upstream call
        // quietly, without a log line for something that is not a failure.
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled() && settled.compareAndSet(false, true)) {
                deadline.cancel(false);
                pending.cancel(true);
                body.cancel();
            }
        });
        return result;
    }

    private HttpClient client() {
        return httpClient != null ? httpClient : SharedHttp.client();
    }

    /**
     * Answers an upstream failure, logging one line for it.
     *
     * <p>A timeout of either kind is the browser's 504; anything else the
     * upstream did (refused, reset, unresolvable, truncated, oversized, or
     * compressed in a way that cannot be decoded) is its 502. Neither body
     * names Fingerly.
     */
    private ProxyResponse failed(Throwable cause, String corsOrigin) {
        Throwable root = cause;
        while ((root instanceof CompletionException || root instanceof ExecutionException)
                && root.getCause() != null) {
            root = root.getCause();
        }
        boolean timedOut = root instanceof TimeoutException || root instanceof HttpTimeoutException;
        try {
            log.accept(timedOut
                    ? "Fingerly proxy: upstream did not answer within " + timeout.toMillis() + " ms"
                    : "Fingerly proxy: upstream failed (" + describe(root) + ")");
        } catch (RuntimeException ignored) {
            // A logging hook that throws must not turn a 502 into a 500.
        }
        List<Map.Entry<String, String>> cors = corsHeaders(corsOrigin);
        return timedOut
                ? ProxyResponse.refusal(504, "Gateway timeout", cors)
                : ProxyResponse.refusal(502, "Bad gateway", cors);
    }

    /** One short, key-free description of a failure: its type and nothing it carries. */
    private static String describe(Throwable cause) {
        if (cause instanceof BoundedBody.TooLargeException) {
            return "response larger than " + BoundedBody.LIMIT + " bytes";
        }
        if (cause instanceof BoundedBody.UnsupportedEncodingException) {
            return "unsupported content coding";
        }
        if (cause instanceof UnusableStatusException) {
            return "unusable status " + ((UnusableStatusException) cause).status;
        }
        return cause == null ? "unknown" : cause.getClass().getSimpleName();
    }

    /** Lets a test see the once-per-process warning again. */
    static void resetWarnings() {
        WARNED_NO_CLIENT_IP.set(false);
    }

    /**
     * Asks the {@link ClientIp} callback for the visitor's address and accepts
     * exactly one IPv4 or IPv6 literal.
     *
     * <p>Anything else (null, empty, a list, a port, a host name, or an
     * exception from the callback) is a configuration error, not a visitor:
     * forwarding it would attribute every identification to a wrong or
     * missing address. The request is refused with a 500 that says so, and
     * the mistake is logged once per process rather than once per request.
     *
     * @return the address, or null when the callback gave none usable
     */
    private String visitorAddress(ProxyRequest request) {
        String address;
        try {
            address = clientIp.resolve(request);
        } catch (RuntimeException broken) {
            address = null;
        }
        if (Keys.isIpLiteral(address)) {
            return address;
        }
        if (WARNED_NO_CLIENT_IP.compareAndSet(false, true)) {
            try {
                log.accept("Fingerly proxy: " + NO_CLIENT_IP
                        + "; requests are refused until clientIp returns one IP address");
            } catch (RuntimeException ignored) {
                // The refusal stands either way.
            }
        }
        return null;
    }

    /**
     * Checks the browser's key, on every request.
     *
     * <p>The browser must supply a public key for the proxy key's environment,
     * which the proxy forwards alongside its own. Any other value, a secret
     * key included, is refused and never sent upstream.
     *
     * @return the public key to forward, or null when the caller is unauthorised
     */
    private String upstreamKeyFor(String presented) {
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
     * <p>Nothing is copied wholesale: all incoming {@code x-fingerly-*},
     * {@code Cookie} and {@code Authorization} headers are stripped, only a
     * validated {@code X-Fingerly-SDK-Platform} is restored, and
     * {@code X-Fingerly-Origin} is sent only when incoming {@code Origin} is
     * non-empty (never synthesized from {@code Host} or the request URL).
     */
    private void applyUpstreamHeaders(
            HttpRequest.Builder upstream,
            ProxyRequest request,
            String apiKey,
            String address,
            String origin,
            String sdkPlatform) {
        String contentType = nonBlank(safe(request.header("Content-Type")));
        upstream.header("Content-Type", contentType == null ? "application/json" : contentType);
        upstream.header("X-API-Key", apiKey);
        upstream.header("X-Fingerly-Client-IP", address);
        // One channel only: the API refuses a proxied request that names both,
        // and a page origin wins, as it does on a direct call.
        if (origin != null) {
            upstream.header("X-Fingerly-Origin", origin);
        } else if (sdkPlatform != null) {
            upstream.header("X-Fingerly-SDK-Platform", sdkPlatform);
        }
        String host = nonBlank(safe(request.header("Host")));
        if (host != null) {
            upstream.header("X-Fingerly-Host", host);
        }
        upstream.header("X-Fingerly-User-Agent", orEmpty(safe(request.header("User-Agent"))));
        upstream.header("User-Agent", USER_AGENT);
        upstream.header("X-Fingerly-Proxy-Version", PROXY_VERSION);
        upstream.header("X-Fingerly-Proxy-Key", key);
        for (String name : FORWARDED_CLIENT_HEADERS) {
            String value = nonBlank(safe(request.header(name)));
            if (value != null) {
                upstream.header(name, value);
            }
        }
        for (String name : SIGNATURE_HEADERS) {
            // A safe value is printable ASCII: one character per byte.
            String value = nonBlank(safe(request.header(name)));
            if (value != null && value.length() <= MAX_SIGNATURE_HEADER_BYTES) {
                upstream.header(name, value);
            }
        }
    }

    /**
     * Returns the upstream response, uncacheable and with only the allowlisted
     * headers. A value naming Fingerly is dropped too, since a blocker can
     * match that as well as a name.
     */
    private static ProxyResponse relay(HttpResponse<byte[]> response, String corsOrigin) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (String name : RELAYED_HEADERS) {
            for (String value : response.headers().allValues(name)) {
                if (!value.toLowerCase(Locale.ROOT).contains("fingerly")) {
                    headers.add(Map.entry(name, value));
                }
            }
        }
        headers.add(Map.entry("Cache-Control", "no-store"));
        headers.addAll(corsHeaders(corsOrigin));

        byte[] body = BODILESS.contains(response.statusCode())
                ? new byte[0]
                : response.body();
        return new ProxyResponse(response.statusCode(), headers, body);
    }

    private String corsOriginFor(ProxyRequest request) {
        if (allowedOrigins.isEmpty() || request == null) {
            return null;
        }
        String origin = nonBlank(safe(request.header("Origin")));
        return origin != null && allowedOrigins.contains(origin) ? origin : null;
    }

    private static List<Map.Entry<String, String>> corsHeaders(String corsOrigin) {
        if (corsOrigin == null) {
            return List.of();
        }
        return List.of(
                Map.entry("Access-Control-Allow-Origin", corsOrigin),
                Map.entry("Access-Control-Allow-Methods", "POST, OPTIONS"),
                Map.entry(
                        "Access-Control-Allow-Headers",
                        "content-type, x-api-key, idempotency-key, x-fingerly-sdk-platform"),
                Map.entry(
                        "Access-Control-Expose-Headers",
                        "x-request-id, ratelimit-limit, ratelimit-remaining, retry-after"),
                Map.entry("Access-Control-Max-Age", "600"),
                Map.entry("Vary", "Origin"));
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

    /** An upstream status the proxy answers 502 for: a redirect, or outside 200 to 599. */
    private static final class UnusableStatusException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        UnusableStatusException(int status) {
            super(null, null, false, false);
            this.status = status;
        }
    }

    /** Raised internally when a streamed body passes the limit. */
    private static final class BodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** One or more plain segments: no empty, dot, encoded or parameterised ones. */
    private static final java.util.regex.Pattern PLAIN_PATH =
            java.util.regex.Pattern.compile("\\A(/[A-Za-z0-9_~!$&'()*+,=:@.-]+)+\\z");

    /**
     * Validates the path the dashboard issued. It is matched exactly: the
     * proxy answers that path and nothing under or around it.
     */
    private static String normalizePath(String value) {
        String path = value.trim();
        boolean plain = PLAIN_PATH.matcher(path).matches();
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                plain = false;
            }
        }
        if (!plain) {
            throw new IllegalArgumentException(
                    "path must be an absolute URL path such as /k7x2qm4a, with no trailing "
                            + "slash, dot segment, percent encoding, query or fragment");
        }
        return path;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * Drops a visitor-supplied value HttpClient would refuse as a header, so
     * one odd byte from a browser costs that header rather than the request.
     */
    private static String safe(String value) {
        return Keys.isHeaderSafe(value) ? value : null;
    }

    private static String nonBlank(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Names the path and upstream; no key is ever included. */
    @Override
    public String toString() {
        return "FingerlyProxy[path=" + path + ", upstream=" + upstreamUrl
                + ", proxyKey=[redacted]]";
    }

    /** Builds a {@link FingerlyProxy}. */
    public static final class Builder {
        private String proxyKey;
        private String path;
        private Set<String> allowedOrigins = Collections.emptySet();
        private String apiVersion = "v1";
        private String upstream;
        private boolean allowInsecureLoopback;
        private long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        private Duration timeout = DEFAULT_TIMEOUT;
        private ClientIp clientIp;
        private HttpClient httpClient;
        private Consumer<String> log;

        private Builder() {}

        /**
         * A proxy key, {@code fly_px_{environment}_…}, held only on your
         * server. Required: {@link #build()} refuses a missing or malformed
         * one. Browsers must present a public key of the same environment.
         */
        public Builder proxyKey(String proxyKey) {
            this.proxyKey = proxyKey;
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
         * {@code /k7x2qm4a}, matched exactly and case-sensitively against the
         * whole raw request path (a trailing slash is a different path);
         * any other path gets 404, or passes through to the application under
         * a filter. Omit it when the framework already routes only that path
         * to the proxy.
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * Cross-origin page origins permitted to post to this proxy. Leave
         * unset when the proxy serves the page's own origin.
         */
        public Builder allowedOrigins(Collection<String> origins) {
            if (origins == null || origins.isEmpty()) {
                this.allowedOrigins = Collections.emptySet();
                return this;
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String origin : origins) {
                normalized.add(Keys.origin(origin));
            }
            this.allowedOrigins = Collections.unmodifiableSet(normalized);
            return this;
        }

        /** Cross-origin page origins permitted to post to this proxy. */
        public Builder allowedOrigins(String... origins) {
            return allowedOrigins(origins == null ? Collections.emptyList() : Arrays.asList(origins));
        }

        /** The API version forwarded upstream. Defaults to {@code v1}. */
        public Builder apiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        /**
         * Overrides the API origin: {@code https://host} with an optional
         * port and nothing else. A path, query, fragment, credentials,
         * whitespace or control byte is refused at {@link #build()}.
         */
        public Builder upstream(String upstream) {
            this.upstream = upstream;
            return this;
        }

        /**
         * For local development only: lets {@link #upstream} be plain
         * {@code http} when its host is {@code 127.0.0.1}, {@code localhost}
         * or {@code [::1]}. Any other plain HTTP upstream is refused either
         * way. Defaults to false.
         */
        public Builder allowInsecureLoopback(boolean allow) {
            this.allowInsecureLoopback = allow;
            return this;
        }

        /** Larger request bodies are refused with 413. Defaults to 1 MiB. */
        public Builder maxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        /**
         * How long to wait for Fingerly, covering the whole response body;
         * past it the browser gets 504. Defaults to 5 seconds.
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
         * Supplies the HTTP client for the upstream call. It must be built
         * with {@link HttpClient.Redirect#NEVER}, the JDK default; any other
         * policy is refused at {@link #build()}. Without one, every proxy and
         * client in the process shares one HTTP/1.1 pool that is recycled
         * every two minutes so DNS changes are picked up.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** No key is ever included. */
        @Override
        public String toString() {
            return "FingerlyProxy.Builder[path=" + path + ", upstream=" + upstream
                    + ", proxyKey=" + (proxyKey == null ? "null" : "[redacted]") + "]";
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
