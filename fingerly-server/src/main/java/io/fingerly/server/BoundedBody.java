package io.fingerly.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Buffers a response body up to a limit, decodes it, and can abandon it.
 *
 * <p>Three things the stock {@code BodyHandlers.ofByteArray()} does not do:
 *
 * <ul>
 *   <li>It stops at {@link #LIMIT}. An upstream that answers with more is
 *       broken, and buffering whatever it sends is how one bad answer becomes
 *       an out-of-memory error in the customer's server.
 *   <li>It decodes {@code gzip} and {@code deflate}. Neither client asks for a
 *       compressed answer, but one that arrives compressed anyway would
 *       otherwise reach the browser as bytes that no longer match the headers
 *       relayed with them. Any other coding is refused.
 *   <li>It can be cancelled. Cancelling the future {@code sendAsync} returns
 *       aborts the exchange only from JDK 16. On JDK 11 to 15 it completes the
 *       future and nothing else: the connection stays open, reading, until the
 *       server finishes. Cancelling the body's own subscription is what
 *       releases it on every JDK.
 * </ul>
 */
final class BoundedBody implements HttpResponse.BodyHandler<byte[]> {

    /** The largest answer either client accepts, after decoding. */
    static final int LIMIT = 4 << 20;

    /** Raised when an answer passes {@link #LIMIT}. */
    static final class TooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        TooLargeException() {
            super("The response body is larger than " + LIMIT + " bytes");
        }
    }

    /** Raised for a {@code Content-Encoding} this cannot decode. */
    static final class UnsupportedEncodingException extends IOException {
        private static final long serialVersionUID = 1L;

        UnsupportedEncodingException() {
            super("The response body uses an unsupported content coding");
        }
    }

    private final int limit;
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private volatile boolean cancelled;

    BoundedBody() {
        this(LIMIT);
    }

    BoundedBody(int limit) {
        this.limit = limit;
    }

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
        String coding = info.headers().firstValue("Content-Encoding").orElse("identity")
                .trim().toLowerCase(Locale.ROOT);
        long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1L);
        return new Subscriber(coding, declared);
    }

    /** Abandons the body, closing the connection it was arriving on. */
    void cancel() {
        cancelled = true;
        Flow.Subscription s = subscription.get();
        if (s != null) {
            s.cancel();
        }
    }

    private final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final String coding;
        private final long declared;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        private Flow.Subscription upstream;

        Subscriber(String coding, long declared) {
            this.coding = coding;
            this.declared = declared;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            upstream = s;
            subscription.set(s);
            if (cancelled) {
                s.cancel();
                return;
            }
            if (!isSupported(coding)) {
                fail(new UnsupportedEncodingException());
                return;
            }
            if (declared > limit) {
                fail(new TooLargeException());
                return;
            }
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (collected.size() + buffer.remaining() > limit) {
                    fail(new TooLargeException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                collected.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (result.isDone()) {
                return;
            }
            try {
                result.complete(decode(coding, collected.toByteArray()));
            } catch (IOException failure) {
                result.completeExceptionally(failure);
            }
        }

        private void fail(IOException failure) {
            upstream.cancel();
            result.completeExceptionally(failure);
        }
    }

    private static boolean isSupported(String coding) {
        return coding.isEmpty() || coding.equals("identity") || coding.equals("gzip")
                || coding.equals("x-gzip") || coding.equals("deflate");
    }

    private byte[] decode(String coding, byte[] body) throws IOException {
        InputStream decoded;
        switch (coding) {
            case "gzip":
            case "x-gzip":
                decoded = new GZIPInputStream(new ByteArrayInputStream(body));
                break;
            case "deflate":
                decoded = new InflaterInputStream(new ByteArrayInputStream(body));
                break;
            default:
                return body;
        }
        try (InputStream in = decoded) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (out.size() + read > limit) {
                    throw new TooLargeException();
                }
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        }
    }
}
