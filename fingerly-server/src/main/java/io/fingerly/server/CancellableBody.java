package io.fingerly.server;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Buffers a response body and can abandon it.
 *
 * <p>Cancelling the future {@code sendAsync} returns aborts the exchange only
 * from JDK 16. On JDK 11 to 15 it completes the future and nothing else: the
 * connection stays open, reading, until the server finishes. Cancelling the
 * body's own subscription is what releases it on every JDK.
 */
final class CancellableBody implements HttpResponse.BodyHandler<byte[]> {

    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private volatile boolean cancelled;

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
        HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<byte[]>() {
            @Override
            public CompletionStage<byte[]> getBody() {
                return delegate.getBody();
            }

            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription.set(s);
                delegate.onSubscribe(s);
                if (cancelled) {
                    s.cancel();
                }
            }

            @Override
            public void onNext(List<ByteBuffer> item) {
                delegate.onNext(item);
            }

            @Override
            public void onError(Throwable throwable) {
                delegate.onError(throwable);
            }

            @Override
            public void onComplete() {
                delegate.onComplete();
            }
        };
    }

    /** Abandons the body, closing the connection it was arriving on. */
    void cancel() {
        cancelled = true;
        Flow.Subscription s = subscription.get();
        if (s != null) {
            s.cancel();
        }
    }
}
