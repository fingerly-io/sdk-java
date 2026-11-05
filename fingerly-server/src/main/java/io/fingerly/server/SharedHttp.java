package io.fingerly.server;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one HTTP client every {@link FingerlyClient} and {@link FingerlyProxy}
 * in a process shares, unless the caller supplies its own.
 *
 * <p>One client means one connection pool: building a proxy or client per
 * request, which is easy to do by accident in a framework that creates beans
 * per request, still reuses connections rather than opening a socket for
 * every call.
 *
 * <p>It speaks HTTP/1.1. Over HTTP/2 the JDK client multiplexes everything on
 * one connection per host, and before JDK 21 it fails requests outright once
 * that connection's concurrent-stream limit is reached rather than opening
 * another, which is exactly what a busy proxy does. Over plain {@code http}
 * it would also add an {@code h2c} upgrade offer to every request. HTTP/1.1
 * pools as many keep-alive connections as there are requests in flight.
 *
 * <p>The client is replaced every {@link #LIFETIME}. A pooled connection that
 * never goes idle is never re-resolved, so without this a process would keep
 * talking to whatever address DNS gave it first. A request already in flight
 * finishes on the client it started with. On JDK 21 and newer the retired
 * client is shut down gracefully. Before that it cannot be closed: its idle
 * connections and selector thread stay until the JDK's keep-alive timeout
 * (20 minutes by default) or until an old-generation collection finds it, so
 * there it is replaced only that often, and at most two are ever open.
 */
final class SharedHttp {

    /** {@code HttpClient.shutdown()} on JDK 21 and newer, null before. */
    private static final Method SHUTDOWN = shutdownMethod();

    /** How long one client serves new requests before it is replaced. */
    static final Duration LIFETIME = SHUTDOWN != null ? Duration.ofMinutes(2) : Duration.ofMinutes(20);

    /** How long a retired client gets to finish what it started. */
    private static final Duration GRACE = Duration.ofMinutes(1);

    private static final class Generation {
        final HttpClient client;
        final long created;

        Generation(HttpClient client, long created) {
            this.client = client;
            this.created = created;
        }
    }

    private static volatile Generation current;
    private static volatile ScheduledExecutorService retirer;

    private SharedHttp() {}

    /** The client for a request starting now. */
    static HttpClient client() {
        return client(System.nanoTime());
    }

    /** The client for a request starting at {@code now}, in {@link System#nanoTime()} terms. */
    static HttpClient client(long now) {
        Generation generation = current;
        if (generation == null || now - generation.created >= LIFETIME.toNanos()) {
            synchronized (SharedHttp.class) {
                generation = current;
                if (generation == null || now - generation.created >= LIFETIME.toNanos()) {
                    Generation retired = generation;
                    generation = new Generation(newClient(), now);
                    current = generation;
                    if (retired != null) {
                        retire(retired.client);
                    }
                }
            }
        }
        return generation.client;
    }

    /** A client configured the way every Fingerly call needs it. */
    static HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                // A redirect would replay a key to another host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Shuts a retired client down after the grace period, where the JDK can. */
    private static void retire(HttpClient client) {
        if (SHUTDOWN == null) {
            return; // Its connections close at the keep-alive timeout or when it is collected.
        }
        scheduler().schedule(() -> {
            try {
                SHUTDOWN.invoke(client);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Nothing else to do; collection still closes it.
            }
        }, GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Method shutdownMethod() {
        try {
            return HttpClient.class.getMethod("shutdown"); // JDK 21+
        } catch (NoSuchMethodException olderJdk) {
            return null;
        }
    }

    private static ScheduledExecutorService scheduler() {
        ScheduledExecutorService scheduler = retirer;
        if (scheduler == null) {
            synchronized (SharedHttp.class) {
                scheduler = retirer;
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                        Thread thread = new Thread(task, "fingerly-http-retirer");
                        thread.setDaemon(true);
                        return thread;
                    });
                    retirer = scheduler;
                }
            }
        }
        return scheduler;
    }
}
