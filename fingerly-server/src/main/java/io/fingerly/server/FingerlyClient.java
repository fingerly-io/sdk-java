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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Fingerly server SDK.
 *
 * <p>Build one and reuse it: it holds a pooled HTTP connection and is safe for
 * concurrent use. Secret keys and webhook secrets belong in server-side code
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
    public static final String VERSION = "0.1.0";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_API_VERSION = "v1";
    private static final String USER_AGENT = "fingerly-java/" + VERSION;

    private final String secretKey;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final EventsService events;

    private FingerlyClient(Builder builder) {
        // Validate the key even when an endpoint is given, so a public, proxy
        // or management key can never reach an API that would accept it.
        Keys.parse(builder.secretKey, Keys.SECRET);
        String origin = Keys.origin(
                builder.endpoint != null ? builder.endpoint : Keys.DEFAULT_ENDPOINT);
        String apiVersion = Keys.apiVersion(builder.apiVersion);

        this.secretKey = builder.secretKey;
        this.baseUrl = origin + "/api/" + apiVersion;
        this.timeout = Keys.positive(builder.timeout, "timeout");
        this.httpClient = builder.httpClient != null ? builder.httpClient : defaultHttpClient();
        this.events = new EventsService(this);
    }

    /**
     * Never follows redirects, so an authenticated request cannot be replayed
     * to another host.
     */
    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** A new builder. {@link Builder#secretKey} is required. */
    public static Builder builder() {
        return new Builder();
    }

    /** Event reads under this client's secret key. */
    public EventsService events() {
        return events;
    }

    /**
     * Performs one authenticated GET and returns the decoded body.
     *
     * <p>Never retries, and the deadline covers receiving the body.
     *
     * <p>That last part is why this waits on a future rather than calling
     * {@code HttpClient.send}: the timeout on an {@link HttpRequest} elapses
     * only until the response headers arrive, so a server that sent headers
     * and then stalled could hold the call open indefinitely. Waiting with a
     * timeout bounds the whole exchange, and cancelling on expiry releases the
     * connection instead of leaving it to finish unread.
     */
    JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .GET()
                .timeout(timeout)
                .header("X-API-Key", secretKey)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();

        BoundedBody body = new BoundedBody();
        CompletableFuture<HttpResponse<byte[]>> pending = httpClient.sendAsync(request, body);

        HttpResponse<byte[]> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            pending.cancel(true);
            body.cancel();
            throw new FingerlyTimeoutException("Fingerly request timed out", expired);
        } catch (ExecutionException failed) {
            throw transportFailure(failed.getCause());
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            body.cancel();
            Thread.currentThread().interrupt();
            throw new FingerlyException("Interrupted while calling the Fingerly API", interrupted);
        }

        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw apiError(response);
        }
        return Json.tree(response.body());
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
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            return new FingerlyApiException(status, null, null, null);
        }
        JsonNode tree;
        try {
            tree = Json.MAPPER.readTree(body);
        } catch (IOException ignored) {
            // A non-JSON body still reports its HTTP status.
            return new FingerlyApiException(status, null, null, null);
        }
        JsonNode error = tree.path("error");
        String code = error.path("code").isTextual() ? redact(error.path("code").asText()) : null;
        String requestId =
                tree.path("request_id").isTextual() ? redact(tree.path("request_id").asText()) : null;

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
        return new FingerlyApiException(status, code, details, requestId);
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

    /** Builds a {@link FingerlyClient}. */
    public static final class Builder {
        private String secretKey;
        private String endpoint;
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

        /** Overrides the Fingerly API origin, for a local API. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
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
