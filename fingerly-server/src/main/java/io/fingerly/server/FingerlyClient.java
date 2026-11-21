package io.fingerly.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Fingerly server SDK.
 *
 * <p>Build one and reuse it: it is safe for concurrent use, and every client
 * and proxy in the process shares one connection pool. Secret keys and webhook secrets belong in server-side code
 * only; a browser must never see either.
 *
 * <pre>{@code
 * FingerlyClient fingerly = FingerlyClient.builder()
 *     .secretKey(System.getenv("FINGERLY_SECRET_KEY"))
 *     .build();
 *
 * Event event = fingerly.events().get(requestId);
 * }</pre>
 */
public final class FingerlyClient {

    /** The SDK version, reported to the API on every request. */
    public static final String VERSION = "0.4.1";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_API_VERSION = "v1";
    private static final String USER_AGENT = "fingerly-java/" + VERSION;

    private final String secretKey;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final EventsService events;
    private final DeviceCheckService deviceCheck;
    private final AgentsService agents;

    private FingerlyClient(Builder builder) {
        // Validate the key even when an endpoint is given, so a public, proxy
        // or management key can never reach an API that would accept it.
        Keys.parse(builder.secretKey, Keys.SECRET);
        String origin = Keys.apiOrigin(
                builder.endpoint != null ? builder.endpoint : Keys.DEFAULT_ENDPOINT,
                builder.allowInsecureLoopback, "endpoint");
        String apiVersion = Keys.apiVersion(builder.apiVersion);

        this.secretKey = builder.secretKey;
        this.baseUrl = origin + "/api/" + apiVersion;
        this.timeout = Keys.positive(builder.timeout, "timeout");
        // Null means the process-wide pool, picked per request so it can be
        // recycled for DNS.
        this.httpClient = builder.httpClient;
        this.events = new EventsService(this);
        this.deviceCheck = new DeviceCheckService(this);
        this.agents = new AgentsService(this);
    }

    /**
     * The supplied client, or the shared pool, which never follows redirects
     * so an authenticated request cannot be replayed to another host.
     */
    private HttpClient client() {
        return httpClient != null ? httpClient : SharedHttp.client();
    }

    /** A new builder. {@link Builder#secretKey} is required. */
    public static Builder builder() {
        return new Builder();
    }

    /** Event reads under this client's secret key. */
    public EventsService events() {
        return events;
    }

    /** Reads and writes the Apple DeviceCheck bits of an iOS event's device. */
    public DeviceCheckService deviceCheck() {
        return deviceCheck;
    }

    /**
     * Reports the requests your server served and reads what sent each one.
     * Most applications use {@link AgentSightings} instead, which batches,
     * remembers the answers and never adds latency.
     */
    public AgentsService agents() {
        return agents;
    }

    /**
     * Performs one authenticated GET and waits for the body.
     *
     * <p>Never retries; the deadline covers receiving the body.
     */
    byte[] get(String path) {
        return await(getAsync(path));
    }

    /** Waits for an exchange, unwrapping its {@link FingerlyException}. */
    <T> T await(CompletableFuture<T> pending) {
        try {
            return pending.get();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            throw cause instanceof FingerlyException
                    ? (FingerlyException) cause
                    : new FingerlyException("The Fingerly request failed", redact(cause));
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new FingerlyException("Interrupted while calling the Fingerly API", interrupted);
        }
    }

    /**
     * Performs one authenticated GET without holding a thread.
     *
     * <p>Never retries. One deadline covers the whole exchange, receiving the
     * body included: the timeout on an {@link HttpRequest} elapses only until
     * the response headers arrive, so a server that sent headers and then
     * trickled could otherwise hold the call open. On expiry the call is
     * abandoned and its connection closed rather than left to finish unread.
     *
     * @return the body of a 2xx answer; otherwise completes exceptionally
     *     with a {@link FingerlyException}. Cancelling it abandons the call.
     */
    CompletableFuture<byte[]> getAsync(String path) {
        return sendAsync("GET", path, null);
    }

    /**
     * Performs one authenticated exchange without holding a thread, with the
     * same deadline as {@link #getAsync}.
     *
     * @param body a JSON body, or null to send none
     */
    CompletableFuture<byte[]> sendAsync(String method, String path, byte[] body) {
        return sendAsync(method, path, body, timeout);
    }

    /**
     * {@link #sendAsync(String, String, byte[])} under its own deadline
     * rather than the client's timeout.
     */
    CompletableFuture<byte[]> sendAsync(String method, String path, byte[] body, Duration deadline) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(deadline)
                .header("X-API-Key", secretKey)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return exchange(builder.build(), deadline);
    }

    private CompletableFuture<byte[]> exchange(HttpRequest request, Duration timeout) {

        BoundedBody body = new BoundedBody();
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = client().sendAsync(request, body);
        } catch (RuntimeException refused) {
            return CompletableFuture.failedFuture(transportFailure(refused));
        }

        CompletableFuture<byte[]> result = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean();
        ScheduledFuture<?> deadline = Deadlines.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                pending.cancel(true);
                body.cancel();
                result.completeExceptionally(
                        new FingerlyTimeoutException("Fingerly request timed out", null));
            }
        }, timeout);
        pending.whenComplete((response, failure) -> {
            deadline.cancel(false);
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
                result.completeExceptionally(transportFailure(cause));
            } else if (response.statusCode() < 200 || response.statusCode() > 299) {
                result.completeExceptionally(apiError(response));
            } else {
                result.complete(response.body());
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled() && settled.compareAndSet(false, true)) {
                deadline.cancel(false);
                pending.cancel(true);
                body.cancel();
            }
        });
        return result;
    }

    /** Classifies why the exchange failed, without leaking the key in doing so. */
    private FingerlyException transportFailure(Throwable cause) {
        if (cause instanceof BoundedBody.TooLargeException) {
            return new FingerlyException(
                    "Fingerly API response was larger than " + BoundedBody.LIMIT + " bytes");
        }
        if (cause instanceof BoundedBody.UnsupportedEncodingException) {
            return new FingerlyException("Fingerly API response used an unsupported content coding");
        }
        if (cause instanceof HttpTimeoutException) {
            return new FingerlyTimeoutException("Fingerly request timed out", redact(cause));
        }
        if (cause instanceof IOException) {
            return new FingerlyConnectionException(
                    "Could not reach the Fingerly API", redact(cause));
        }
        return new FingerlyException("The Fingerly request failed", redact(cause));
    }

    /**
     * Maps a failed response to a redacted {@link FingerlyApiException}.
     *
     * <p>Only the status and the structured fields the API documents are
     * surfaced. The raw body is deliberately dropped rather than echoed.
     */
    private FingerlyApiException apiError(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        Integer retryAfter = retryAfter(response.headers().firstValue("Retry-After").orElse(null));
        String scope = response.headers().firstValue("Fingerly-RateLimit-Scope")
                .filter(Keys::isHeaderSafe)
                .map(this::redact)
                .orElse(null);
        String headerRequestId = response.headers().firstValue("X-Request-Id")
                .filter(Keys::isHeaderSafe)
                .map(this::redact)
                .orElse(null);
        byte[] body = response.body();
        JsonNode tree = null;
        if (body != null && body.length > 0) {
            try {
                tree = Json.MAPPER.readTree(body);
            } catch (IOException ignored) {
                // A non-JSON body still reports its HTTP status, and nothing of
                // what it said.
                tree = null;
            }
        }
        if (tree == null || !tree.isObject()) {
            return new FingerlyApiException(status, null, null, headerRequestId, retryAfter, scope);
        }
        JsonNode error = tree.path("error");
        JsonNode codeNode = error.path("code").isTextual() ? error.path("code") : tree.path("code");
        String code = codeNode.isTextual() ? redact(codeNode.asText()) : null;
        String requestId = tree.path("request_id").isTextual()
                ? redact(tree.path("request_id").asText())
                : headerRequestId;

        JsonNode details = null;
        if (!error.path("details").isMissingNode() && !error.path("details").isNull()) {
            // Re-reading the redacted text covers nested values without
            // walking the tree.
            try {
                details = Json.MAPPER.readTree(redact(error.path("details").toString()));
            } catch (IOException ignored) {
                details = null;
            }
        }
        if (status == 409 && "event_already_consumed".equals(code)) {
            return new EventAlreadyConsumedException(
                    status, code, details, requestId, retryAfter, redact(consumedAt(tree, error)));
        }
        return new FingerlyApiException(status, code, details, requestId, retryAfter, scope);
    }

    /**
     * The {@code consumed_at} of an {@code event_already_consumed} answer: at
     * the top level, inside {@code error}, or as an {@code error.details}
     * entry whose {@code issue} holds the time. Null when none is there.
     */
    private static String consumedAt(JsonNode tree, JsonNode error) {
        if (tree.path("consumed_at").isTextual()) {
            return tree.path("consumed_at").asText();
        }
        if (error.path("consumed_at").isTextual()) {
            return error.path("consumed_at").asText();
        }
        JsonNode details = error.path("details");
        if (details.isArray()) {
            for (JsonNode detail : details) {
                if ("consumed_at".equals(detail.path("field").asText())
                        && detail.path("issue").isTextual()) {
                    return detail.path("issue").asText();
                }
            }
        }
        return null;
    }

    /**
     * Parses {@code Retry-After}: delta-seconds, or an HTTP date turned into
     * seconds from now (never negative).
     *
     * @return the seconds to wait, or null when the header is absent or unreadable
     */
    static Integer retryAfter(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.matches("[0-9]{1,9}")) {
            return Integer.valueOf(text);
        }
        try {
            java.time.ZonedDateTime at = java.time.ZonedDateTime.parse(
                    text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = Duration.between(java.time.Instant.now(), at.toInstant()).getSeconds();
            return (int) Math.max(0, Math.min(seconds, Integer.MAX_VALUE));
        } catch (java.time.format.DateTimeParseException unreadable) {
            return null;
        }
    }

    /** Strips the secret key from anything an error may carry to logs. */
    private String redact(String value) {
        return value == null ? null : value.replace(secretKey, "[redacted]");
    }

    /**
     * Strips the secret key from a transport failure, whose message can
     * include the request URL.
     */
    private Throwable redact(Throwable cause) {
        String message = cause.getMessage();
        if (message == null || !message.contains(secretKey)) {
            return cause;
        }
        return new IOException(redact(message));
    }

    /** Names the endpoint; the secret key is never included. */
    @Override
    public String toString() {
        return "FingerlyClient[baseUrl=" + baseUrl + ", secretKey=[redacted]]";
    }

    /** Builds a {@link FingerlyClient}. */
    public static final class Builder {
        private String secretKey;
        private String endpoint;
        private boolean allowInsecureLoopback;
        private String apiVersion = DEFAULT_API_VERSION;
        private Duration timeout = DEFAULT_TIMEOUT;
        private HttpClient httpClient;

        private Builder() {}

        /**
         * A server-only key, {@code fly_sk_{environment}_…}, whose prefix
         * names the environment read. Required.
         */
        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        /**
         * Overrides the Fingerly API origin: {@code https://host} with an
         * optional port and nothing else. A path, query, fragment,
         * credentials, whitespace or control byte is refused at
         * {@link #build()}.
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * For local development only: lets {@link #endpoint} be plain
         * {@code http} when its host is {@code 127.0.0.1}, {@code localhost}
         * or {@code [::1]}. Any other plain HTTP endpoint is refused either
         * way. Defaults to false.
         */
        public Builder allowInsecureLoopback(boolean allow) {
            this.allowInsecureLoopback = allow;
            return this;
        }

        /** The API route version. Defaults to {@code v1}. */
        public Builder apiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        /**
         * The deadline for each request, covering the response body. Defaults
         * to 10 seconds.
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Supplies the HTTP client, for a custom executor, proxy or TLS setup.
         *
         * <p>Configure it with {@link HttpClient.Redirect#NEVER}. A client that
         * follows redirects can replay the secret key to another host.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** The secret key is never included. */
        @Override
        public String toString() {
            return "FingerlyClient.Builder[endpoint=" + endpoint
                    + ", secretKey=" + (secretKey == null ? "null" : "[redacted]") + "]";
        }

        /**
         * Builds the client, validating every option first.
         *
         * @throws IllegalArgumentException if the key, endpoint, version or
         *     timeout is invalid. Nothing is sent until then.
         */
        public FingerlyClient build() {
            return new FingerlyClient(this);
        }
    }
}
