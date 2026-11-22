package io.fingerly.server;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spends the Apple DeviceCheck tokens held for an iOS event to read and to
 * write the device's two bits. Reached as {@code fingerly.deviceCheck()}.
 *
 * <p>An identification holds two single-use tokens, one for a read and one
 * for a write, so a "check, then mark" flow uses one request ID: read on the
 * identification that asks, for example, for a free trial, record the grant
 * in your own data, then write with the same request ID.
 *
 * <p>A call is retried, with the identical request, only after a
 * {@code 503} or a transport failure, up to twice. The API keeps a token
 * only for a retry of the same request: a second read, or a write with
 * other bits, answers {@code 409 device_check_spent}.
 */
public final class DeviceCheckService {

    /** The waits before the second and third attempts. */
    private static final Duration[] RETRY_DELAYS = {Duration.ofMillis(250), Duration.ofSeconds(1)};

    /** The longest {@code Retry-After} a retry waits for before giving up. */
    private static final int MAX_RETRY_AFTER_SECONDS = 5;

    private final FingerlyClient client;

    DeviceCheckService(FingerlyClient client) {
        this.client = client;
    }

    /**
     * Reads the device's two bits from Apple, spending the event's read token.
     *
     * @param requestId the {@code request_id} of an iOS identification made
     *     within the last hour
     * @throws FingerlyApiException on any non-2xx answer; branch on
     *     {@link FingerlyApiException#isDeviceCheckNotHeld()},
     *     {@link FingerlyApiException#isDeviceCheckSpent()} and the others
     * @throws FingerlyTimeoutException if every attempt exceeds the client timeout
     * @throws IllegalArgumentException if requestId is null, empty, "." or ".."
     */
    public DeviceCheckBits query(String requestId) {
        return client.await(queryAsync(requestId));
    }

    /** {@link #query} without holding a thread. */
    public CompletableFuture<DeviceCheckBits> queryAsync(String requestId) {
        String path = path(requestId) + "/query";
        return withRetries("POST", path, null)
                .thenApply(body -> DeviceCheckBits.from(Json.tree(body)));
    }

    /**
     * Sets {@code bit0}, {@code bit1} or both with Apple, spending the event's
     * write token, which a query on the same event leaves unspent. A bit left
     * out keeps its value.
     *
     * @param requestId the {@code request_id} of an iOS identification made
     *     within the last hour
     * @param bits the bits to set, from {@link DeviceCheckWrite#builder()}
     * @throws FingerlyApiException on any non-2xx answer
     * @throws IllegalArgumentException if requestId is invalid or bits is null
     */
    public DeviceCheckWriteResult write(String requestId, DeviceCheckWrite bits) {
        return client.await(writeAsync(requestId, bits));
    }

    /** {@link #write} without holding a thread. */
    public CompletableFuture<DeviceCheckWriteResult> writeAsync(String requestId, DeviceCheckWrite bits) {
        if (bits == null) {
            throw new IllegalArgumentException("bits is required");
        }
        String path = path(requestId);
        return withRetries("PUT", path, bits.body())
                .thenApply(body -> DeviceCheckWriteResult.from(Json.tree(body)));
    }

    /** Sends the same request again after a 503 or a transport failure. */
    private CompletableFuture<byte[]> withRetries(String method, String path, byte[] body) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        AtomicReference<Future<?>> current = new AtomicReference<>();
        attempt(method, path, body, 0, result, current);
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                Future<?> inFlight = current.get();
                if (inFlight != null) {
                    inFlight.cancel(true);
                }
            }
        });
        return result;
    }

    private void attempt(String method, String path, byte[] body, int retries,
            CompletableFuture<byte[]> result, AtomicReference<Future<?>> current) {
        if (result.isDone()) {
            return;
        }
        CompletableFuture<byte[]> pending = client.sendAsync(method, path, body);
        current.set(pending);
        pending.whenComplete((answer, failure) -> {
            if (failure == null) {
                result.complete(answer);
                return;
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            Duration wait = retries < RETRY_DELAYS.length ? retryDelay(cause, retries) : null;
            if (wait == null) {
                result.completeExceptionally(cause);
                return;
            }
            current.set(Deadlines.schedule(
                    () -> attempt(method, path, body, retries + 1, result, current), wait));
        });
    }

    /** The wait before retrying after {@code cause}, or null when it is final. */
    private static Duration retryDelay(Throwable cause, int retries) {
        if (cause instanceof FingerlyConnectionException || cause instanceof FingerlyTimeoutException) {
            return RETRY_DELAYS[retries];
        }
        if (cause instanceof FingerlyApiException && ((FingerlyApiException) cause).getStatus() == 503) {
            Integer after = ((FingerlyApiException) cause).getRetryAfter();
            if (after == null) {
                return RETRY_DELAYS[retries];
            }
            return after <= MAX_RETRY_AFTER_SECONDS ? Duration.ofSeconds(after) : null;
        }
        return null;
    }

    private static String path(String requestId) {
        if (requestId == null || requestId.isEmpty()
                || requestId.equals(".") || requestId.equals("..")) {
            throw new IllegalArgumentException("requestId is required");
        }
        String segment = URLEncoder.encode(requestId, StandardCharsets.UTF_8).replace("+", "%20");
        return "/events/" + segment + "/device-check";
    }

    @Override
    public String toString() {
        return "DeviceCheckService[" + client + "]";
    }
}
