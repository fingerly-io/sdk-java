package io.fingerly.server;

import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Sees the AI crawlers and fetchers that never run a page's JavaScript, by
 * reporting the requests your server served, and answers your code from what
 * Fingerly said about them.
 *
 * <p>Build one per process and mount it with a filter:
 * {@code FingerlyAgentSightingsFilter} for Jakarta or javax servlets,
 * {@code FingerlyAgentSightingsWebFilter} for WebFlux. After each response the
 * filter decides from your own response whether it is worth reporting (HTML
 * pages, {@code /robots.txt}, {@code /llms.txt} and {@code /llms-full.txt} by
 * default), and holds the request in memory. A daemon thread sends what it
 * holds in batches, off every request thread, and remembers each answer for
 * as long as the API allows.
 *
 * <pre>{@code
 * AgentSightings sightings = AgentSightings.builder()
 *     .client(fingerly)
 *     .clientIp(request -> request.header("CF-Connecting-IP"))
 *     .build();
 *
 * AgentSighting answer = sightings.lookup(request);
 * if (VisitorKind.AI_BOT.equals(answer.getVisitorKind())) {
 *     // an AI agent the API has already seen at this address
 * }
 * }</pre>
 *
 * <p>The SDK never classifies a request itself: it forwards the user agent,
 * the address, the host, the method and the Web Bot Auth headers, and the API
 * decides. It never sends the path, the query string, cookies,
 * {@code Authorization}, any other header, the body or the response, and
 * never logs any of what it sends.
 *
 * <p>Memory is bounded: at most {@link #MAX_CACHE_CAPACITY} remembered
 * answers (default {@link #DEFAULT_CACHE_CAPACITY}, about 1.5 MB) and at most
 * 1000 requests waiting to be reported. It fails open: {@link #lookup} never
 * waits on the network and never throws, and answers
 * {@link AgentSighting#UNKNOWN} whenever it does not know.
 */
public final class AgentSightings implements AutoCloseable {

    /** The number of answers remembered unless {@link Builder#cacheCapacity} says otherwise. */
    public static final int DEFAULT_CACHE_CAPACITY = 4096;

    /** The most answers {@link Builder#cacheCapacity} may allow. */
    public static final int MAX_CACHE_CAPACITY = 65_536;

    /** The response content types reported unless {@link Builder#contentTypes} replaces them. */
    public static final List<String> DEFAULT_CONTENT_TYPES =
            List.of("text/html", "application/xhtml+xml");

    /** Paths reported whatever their status and content type, unless excluded. */
    public static final List<String> ALWAYS_REPORTED_PATHS =
            List.of("/robots.txt", "/llms.txt", "/llms-full.txt");

    /** The most requests held for reporting at once. */
    static final int MAX_PENDING = 1000;

    /** A flush sends at most this many requests, and a buffer this full flushes. */
    static final int BATCH_ITEMS = AgentsService.MAX_ITEMS;

    /** A buffer holding this many estimated bytes flushes, and a flush sends at most this many. */
    static final long BATCH_BYTES = 64 * 1024;

    /** The longest a request waits to be reported. */
    static final long FLUSH_AFTER = Duration.ofSeconds(2).toNanos();

    /** The deadline of one flush. */
    static final Duration DEADLINE = Duration.ofSeconds(5);

    /** The longest header value reported, in UTF-8 bytes. */
    static final int MAX_HEADER_BYTES = 4096;

    private static final long AUTH_PAUSE = Duration.ofSeconds(60).toNanos();
    private static final long RATE_LIMIT_PAUSE = Duration.ofSeconds(60).toNanos();
    private static final long BACKOFF_START = Duration.ofSeconds(1).toNanos();
    private static final long BACKOFF_MAX = Duration.ofSeconds(60).toNanos();
    private static final long LOG_EVERY = Duration.ofSeconds(60).toNanos();
    private static final long TICK_MILLIS = 250;
    /** The longest an answer is remembered, whatever ttl the API gave. */
    static final long MAX_TTL_SECONDS = 86_400L;

    /** The longest {@link #flush} waits for one flush, its deadline and a second of margin. */
    static final Duration FLUSH_WAIT = DEADLINE.plusSeconds(1);

    /** Where lines go when the builder was given no {@code log}. */
    private static final System.Logger LOGGER = System.getLogger(AgentSightings.class.getName());

    /** Sends one batch. The real one is the API call under {@link #DEADLINE}. */
    @FunctionalInterface
    interface Sender {
        CompletableFuture<AgentSightingsResult> send(List<AgentSightingReport> items);
    }

    /** One request as it is keyed and reported. */
    static final class Observation {
        final String userAgent;
        final String clientIp;
        final String host;
        final String method;
        final String signature;
        final String signatureInput;
        final String signatureAgent;
        final boolean signed;
        final String key;
        final long size;

        Observation(String userAgent, String clientIp, String host, String method,
                String signature, String signatureInput, String signatureAgent) {
            this.userAgent = userAgent;
            this.clientIp = clientIp;
            this.host = host;
            this.method = method;
            this.signature = signature;
            this.signatureInput = signatureInput;
            this.signatureAgent = signatureAgent;
            this.signed = signature != null && signatureInput != null;
            this.key = SightingKey.of(userAgent, clientIp,
                    signatureAgent == null ? "" : signatureAgent, signed);
            this.size = utf8Length(userAgent) + utf8Length(clientIp)
                    + utf8Length(host) + utf8Length(method) + utf8Length(signature)
                    + utf8Length(signatureInput) + utf8Length(signatureAgent);
        }
    }

    /** One request waiting to be reported, with how often it was seen. */
    private static final class Pending {
        final String slot;
        final Observation observation;
        final long born;
        long count;

        Pending(String slot, Observation observation, long born, long count) {
            this.slot = slot;
            this.observation = observation;
            this.born = born;
            this.count = count;
        }

        AgentSightingReport report() {
            return AgentSightingReport.builder()
                    .userAgent(observation.userAgent)
                    .clientIp(observation.clientIp)
                    .host(observation.host)
                    .method(observation.method)
                    .signature(observation.signature)
                    .signatureInput(observation.signatureInput)
                    .signatureAgent(observation.signatureAgent)
                    .count(count)
                    .build();
        }
    }

    /** A bounded LRU of the API's answers, each with its expiry. */
    static final class AnswerCache {
        private static final class Remembered {
            final AgentSighting answer;
            final long expires;

            Remembered(AgentSighting answer, long expires) {
                this.answer = answer;
                this.expires = expires;
            }
        }

        private final LinkedHashMap<String, Remembered> entries;
        final int capacity;
        private String rulesVersion;

        AnswerCache(int capacity) {
            this.capacity = capacity;
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Remembered> eldest) {
                    return size() > capacity;
                }
            };
        }

        /** The unexpired answer for a key, refreshing its recency, or null. */
        synchronized AgentSighting get(String key, long now) {
            Remembered entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            if (now - entry.expires >= 0) {
                entries.remove(key);
                return null;
            }
            return entry.answer;
        }

        /** Drops every answer when the rules changed since the cache was filled. */
        synchronized void adopt(String version) {
            if (rulesVersion != null && !rulesVersion.equals(version)) {
                entries.clear();
            }
            rulesVersion = version;
        }

        /** Remembers an answer for its ttl, at most a day; a ttl of 0 is never remembered. */
        synchronized void put(String key, AgentSighting answer, long ttlSeconds, long now) {
            if (ttlSeconds <= 0) {
                return;
            }
            long ttl = Math.min(ttlSeconds, MAX_TTL_SECONDS);
            entries.put(key, new Remembered(answer, now + TimeUnit.SECONDS.toNanos(ttl)));
        }

        synchronized int size() {
            return entries.size();
        }
    }

    /** Lets one kind of line through at most once a minute. */
    private static final class RateLimitedLog {
        private boolean logged;
        private long loggedAt;

        synchronized boolean allow(long now) {
            if (logged && now - loggedAt < LOG_EVERY) {
                return false;
            }
            logged = true;
            loggedAt = now;
            return true;
        }
    }

    private final FingerlyProxy.ClientIp clientIp;
    private final Set<String> contentTypes;
    private final List<String> includePrefixes;
    private final List<String> excludePrefixes;
    private final Consumer<String> log;
    private final Sender sender;
    private final LongSupplier clock;
    private final Supplier<Instant> wallClock;
    private final AnswerCache cache;
    private final ScheduledThreadPoolExecutor flusher;

    private final Object lock = new Object();
    private LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();
    private long pendingBytes;
    private long signedSequence;
    /** The flush in flight; completes true when its batch was answered. */
    private CompletableFuture<Boolean> inFlight;
    private int inFlightItems;
    private boolean paused;
    private long pausedUntil;
    private long backoff = BACKOFF_START;

    private final RateLimitedLog invalidLog = new RateLimitedLog();
    private final RateLimitedLog authLog = new RateLimitedLog();
    private final RateLimitedLog clientIpLog = new RateLimitedLog();

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean wakeQueued = new AtomicBoolean();
    private volatile boolean closed;

    private AgentSightings(Builder builder) {
        if (builder.sender == null && builder.client == null) {
            throw new IllegalArgumentException("client is required");
        }
        if (builder.clientIp == null) {
            throw new IllegalArgumentException("clientIp is required");
        }
        if (builder.cacheCapacity < 1) {
            throw new IllegalArgumentException("cacheCapacity must be at least 1");
        }
        this.clientIp = builder.clientIp;
        this.contentTypes = builder.contentTypes;
        this.includePrefixes = builder.includePrefixes;
        this.excludePrefixes = builder.excludePrefixes;
        this.log = builder.log != null
                ? builder.log
                : line -> LOGGER.log(System.Logger.Level.WARNING, line);
        FingerlyClient client = builder.client;
        this.sender = builder.sender != null
                ? builder.sender
                : items -> client.agents().reportSightingsAsync(items, DEADLINE);
        this.clock = builder.clock != null ? builder.clock : System::nanoTime;
        this.wallClock = builder.wallClock != null ? builder.wallClock : Instant::now;
        this.cache = new AnswerCache(Math.min(builder.cacheCapacity, MAX_CACHE_CAPACITY));
        this.flusher = builder.background ? startFlusher() : null;
    }

    /** A new builder. {@code client} and {@code clientIp} are required. */
    public static Builder builder() {
        return new Builder();
    }

    /** One daemon thread, so the flusher never keeps the process alive. */
    private ScheduledThreadPoolExecutor startFlusher() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "fingerly-agent-sightings");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
        return executor;
    }

    /**
     * What the API last said about this request, from memory only.
     *
     * <p>Never waits on the network and never throws. Answers
     * {@link AgentSighting#UNKNOWN} when nothing unexpired is remembered for
     * the request's user agent, address and {@code Signature-Agent}, when the
     * request cannot be reported (no usable client address, or a
     * {@code User-Agent} over 4096 bytes), and for a signed request, which the
     * API checks every time. Treat {@code UNKNOWN} as "serve as usual".
     *
     * @param request the request, as an adapter describes it
     */
    public AgentSighting lookup(ProxyRequest request) {
        try {
            Observation observation = request == null ? null : observe(request);
            if (observation == null) {
                return AgentSighting.UNKNOWN;
            }
            AgentSighting answer = cache.get(observation.key, clock.getAsLong());
            return answer == null ? AgentSighting.UNKNOWN : answer;
        } catch (RuntimeException failOpen) {
            return AgentSighting.UNKNOWN;
        }
    }

    /**
     * Reports whether a response is one this instance reports, from facts
     * about your own resource only, never about the visitor.
     *
     * <p>An excluded path prefix wins over everything. Otherwise
     * {@link #ALWAYS_REPORTED_PATHS} and every included path prefix are
     * reported whatever their content type, and any other response when its
     * media type is one of the content types. The status does not matter.
     *
     * @param path the request path, without the query string
     * @param contentType the response's {@code Content-Type}, or null
     */
    public boolean reports(String path, String contentType) {
        String target = path == null ? "" : path;
        int query = target.indexOf('?');
        if (query >= 0) {
            target = target.substring(0, query);
        }
        for (String prefix : excludePrefixes) {
            if (target.startsWith(prefix)) {
                return false;
            }
        }
        if (ALWAYS_REPORTED_PATHS.contains(target)) {
            return true;
        }
        for (String prefix : includePrefixes) {
            if (target.startsWith(prefix)) {
                return true;
            }
        }
        String media = mediaType(contentType);
        return media != null && contentTypes.contains(media);
    }

    /**
     * Holds one served request for reporting, if its response is in scope.
     *
     * <p>The filters call this after the response is produced. It only
     * touches memory: a repeat of a request already waiting adds to its
     * count, and the send happens later on the flusher thread. Never throws.
     *
     * @param request the request, as an adapter describes it
     * @param contentType the response's {@code Content-Type}, or null
     */
    public void report(ProxyRequest request, String contentType) {
        try {
            if (closed || request == null || !reports(request.path(), contentType)) {
                return;
            }
            Observation observation = observe(request);
            if (observation != null) {
                record(observation);
            }
        } catch (RuntimeException failOpen) {
            // Reporting never affects the response.
        }
    }

    /**
     * Sends everything waiting now and waits for the answers, a batch at a
     * time, each within 5 seconds.
     *
     * <p>For serverless and other short-lived runtimes, before the invocation
     * ends; a long-lived process does not need it. Waits for a flush already
     * in flight first, and stops once the buffer is empty, once the API asks
     * to wait (a rate limit, a refused key or a backoff), or once a batch is
     * dropped. Waits at most 6 seconds for any one flush. Never throws.
     */
    public void flush() {
        // Every batch takes at least one request, so this bounds the loop even
        // while requests keep arriving.
        int budget;
        synchronized (lock) {
            budget = pending.size() + 1;
        }
        for (int i = 0; i <= budget; i++) {
            CompletableFuture<Boolean> running;
            boolean waited;
            synchronized (lock) {
                running = inFlight;
            }
            waited = running != null;
            if (running == null) {
                running = start(true);
                if (running == null) {
                    return;
                }
            }
            try {
                if (!running.get(FLUSH_WAIT.toMillis(), TimeUnit.MILLISECONDS) && !waited) {
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException unexpected) {
                return;
            }
        }
    }

    /**
     * Stops the flusher thread, then sends what is waiting, as
     * {@link #flush} does. Later reports are ignored; {@link #lookup} keeps
     * answering from memory.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (flusher != null) {
            flusher.shutdownNow();
        }
        flush();
    }

    /**
     * How many requests were not held for reporting because 1000 were
     * already waiting. Their lookups stay {@link AgentSighting#UNKNOWN}.
     */
    public long getDroppedCount() {
        return dropped.get();
    }

    /** The requests waiting, folded repeats counted once. */
    int pendingSize() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** The count waiting for the first pending request with this key, or 0. */
    long pendingCount(String key) {
        synchronized (lock) {
            for (Pending item : pending.values()) {
                if (item.observation.key.equals(key)) {
                    return item.count;
                }
            }
            return 0;
        }
    }

    int cacheSize() {
        return cache.size();
    }

    int cacheCapacity() {
        return cache.capacity;
    }

    /** Reads what is reported and keyed from a request, or null when it cannot be reported. */
    Observation observe(ProxyRequest request) {
        String userAgent = request.header("User-Agent");
        if (userAgent == null) {
            userAgent = "";
        }
        if (utf8Length(userAgent) > MAX_HEADER_BYTES) {
            return null;
        }
        String address = resolveAddress(request);
        if (address == null) {
            return null;
        }
        return new Observation(userAgent, address,
                request.header("Host"),
                request.method(),
                bounded(request.header("Signature")),
                bounded(request.header("Signature-Input")),
                bounded(request.header("Signature-Agent")));
    }

    /** The canonical client address from the trusted callback, or null, logged once a minute. */
    private String resolveAddress(ProxyRequest request) {
        String address;
        try {
            address = SightingKey.canonicalIp(clientIp.resolve(request));
        } catch (RuntimeException broken) {
            address = null;
        }
        if (address == null && clientIpLog.allow(clock.getAsLong())) {
            say("Fingerly agent sightings: " + FingerlyProxy.NO_CLIENT_IP
                    + "; those requests are not reported");
        }
        return address;
    }

    private void record(Observation observation) {
        boolean due;
        synchronized (lock) {
            long now = clock.getAsLong();
            Pending existing = observation.signed ? null : pending.get(observation.key);
            if (existing != null) {
                if (existing.count < AgentsService.MAX_COUNT) {
                    existing.count++;
                }
            } else if (pending.size() + inFlightItems >= MAX_PENDING) {
                // The cap covers the batch in flight too, so a batch kept
                // after a failure always fits back.
                dropped.incrementAndGet();
                return;
            } else {
                // A signed request is never folded: each is its own item.
                String slot = observation.signed
                        ? observation.key + "#" + (++signedSequence)
                        : observation.key;
                pending.put(slot, new Pending(slot, observation, now, 1));
                pendingBytes += observation.size;
            }
            due = pending.size() >= BATCH_ITEMS || pendingBytes >= BATCH_BYTES;
        }
        if (due) {
            wake();
        }
    }

    /** Asks the flusher thread to look now, without waiting for its next tick. */
    private void wake() {
        if (flusher == null || !wakeQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            flusher.execute(() -> {
                wakeQueued.set(false);
                tick();
            });
        } catch (RejectedExecutionException shutDown) {
            wakeQueued.set(false);
        }
    }

    /** Starts a flush without waiting for it, threshold or not; for tests. */
    CompletableFuture<Boolean> flushNow() {
        return start(true);
    }

    /** One look at the buffer: flushes when a threshold is reached. */
    void tick() {
        try {
            start(false);
        } catch (RuntimeException unexpected) {
            // The flusher must survive anything; the next tick tries again.
        }
    }

    /**
     * Starts one flush, unless one is in flight, the API asked to wait, or
     * (without {@code force}) no threshold is reached.
     *
     * @return the flush in flight, or null when none was started
     */
    private CompletableFuture<Boolean> start(boolean force) {
        List<Pending> batch;
        CompletableFuture<Boolean> done;
        synchronized (lock) {
            if (inFlight != null) {
                return inFlight;
            }
            long now = clock.getAsLong();
            if (paused && now - pausedUntil < 0) {
                return null;
            }
            paused = false;
            if (pending.isEmpty() || (!force && !due(now))) {
                return null;
            }
            batch = take();
            done = new CompletableFuture<>();
            inFlight = done;
            inFlightItems = batch.size();
        }
        List<AgentSightingReport> reports = new ArrayList<>(batch.size());
        for (Pending item : batch) {
            reports.add(item.report());
        }
        CompletableFuture<AgentSightingsResult> call;
        try {
            call = sender.send(reports);
        } catch (RuntimeException refused) {
            call = CompletableFuture.failedFuture(refused);
        }
        call.whenComplete((result, failure) -> {
            boolean answered = false;
            try {
                answered = finish(batch, result, unwrap(failure));
            } catch (RuntimeException unexpected) {
                synchronized (lock) {
                    if (inFlight == done) {
                        inFlight = null;
                        inFlightItems = 0;
                    }
                }
            } finally {
                done.complete(answered);
                if (!closed) {
                    boolean again;
                    synchronized (lock) {
                        again = !paused && !pending.isEmpty() && due(clock.getAsLong());
                    }
                    if (again) {
                        wake();
                    }
                }
            }
        });
        return done;
    }

    /** Whether a threshold is reached. Called under the lock. */
    private boolean due(long now) {
        if (pending.isEmpty()) {
            return false;
        }
        if (pending.size() >= BATCH_ITEMS || pendingBytes >= BATCH_BYTES) {
            return true;
        }
        return now - pending.values().iterator().next().born >= FLUSH_AFTER;
    }

    /** Takes the oldest requests, up to 200 and 64 KiB (at least one). Called under the lock. */
    private List<Pending> take() {
        List<Pending> batch = new ArrayList<>();
        long bytes = 0;
        Iterator<Pending> oldest = pending.values().iterator();
        while (oldest.hasNext() && batch.size() < BATCH_ITEMS) {
            Pending item = oldest.next();
            if (!batch.isEmpty() && bytes + item.observation.size > BATCH_BYTES) {
                break;
            }
            batch.add(item);
            bytes += item.observation.size;
            pendingBytes -= item.observation.size;
            oldest.remove();
        }
        return batch;
    }

    /**
     * Applies the API's answer, or its failure, to the batch that was sent,
     * and ends the flush in flight.
     *
     * @return true when the batch was answered
     */
    private boolean finish(List<Pending> batch, AgentSightingsResult result, Throwable failure) {
        long now = clock.getAsLong();
        if (failure == null && result != null && result.getItems().size() == batch.size()) {
            cache.adopt(result.getRulesVersion());
            List<AgentSighting> answers = result.getItems();
            for (int i = 0; i < batch.size(); i++) {
                AgentSighting answer = answers.get(i);
                if (answer != null) {
                    cache.put(batch.get(i).observation.key, answer, answer.getTtl(), now);
                }
            }
            synchronized (lock) {
                endFlight();
                backoff = BACKOFF_START;
            }
            return true;
        }
        synchronized (lock) {
            endFlight();
            if (failure == null) {
                invalid("the API answered a different number of requests than were reported");
            } else if (failure instanceof FingerlyApiException) {
                FingerlyApiException api = (FingerlyApiException) failure;
                int status = api.getStatus();
                if (status == 429) {
                    keep(batch);
                    pause(now, rateLimitPause(api));
                } else if (status == 401 || status == 403) {
                    pause(now, AUTH_PAUSE);
                    if (authLog.allow(now)) {
                        say("Fingerly agent sightings: the API refused the secret key (HTTP " + status
                                + "); the batch was dropped and reporting pauses for 60 seconds."
                                + " Check that the key is right and not revoked");
                    }
                } else if (status == 503) {
                    keep(batch);
                    backOff(now);
                } else {
                    invalid("the API refused a report (HTTP " + status + ")");
                }
            } else if (failure instanceof FingerlyTimeoutException) {
                if (failure.getCause() instanceof HttpConnectTimeoutException) {
                    // Never connected, so nothing was counted: safe to send again.
                    keep(batch);
                    backOff(now);
                }
                // Otherwise the request may have been sent and counted, and
                // sending it again would count it twice: it is dropped.
            } else if (failure instanceof FingerlyConnectionException) {
                if (!((FingerlyConnectionException) failure).requestWritten()) {
                    // Refused, unresolvable, TLS, or closed before the whole
                    // request was written: nothing was counted, safe to send again.
                    keep(batch);
                    backOff(now);
                }
                // Closed, reset or cut short once the request was written:
                // the API may have counted it, so it is dropped like a timeout.
            } else {
                invalid("the API answered with something that is not a report answer");
            }
        }
        return false;
    }

    /** Called under the lock. */
    private void endFlight() {
        inFlight = null;
        inFlightItems = 0;
    }

    /** The wait a 429 asks for. */
    private long rateLimitPause(FingerlyApiException refusal) {
        Integer after = refusal.getRetryAfter();
        if (after != null) {
            return TimeUnit.SECONDS.toNanos(Math.max(1, after));
        }
        if ("daily".equals(refusal.getRateLimitScope())) {
            return untilNextUtcDay(wallClock.get()).toNanos();
        }
        return RATE_LIMIT_PAUSE;
    }

    /** The time from {@code now} to the next midnight UTC, when a daily allowance resets. */
    static Duration untilNextUtcDay(Instant now) {
        Instant midnight = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        return Duration.between(now, midnight);
    }

    /** Called under the lock. */
    private void pause(long now, long nanos) {
        paused = true;
        pausedUntil = now + nanos;
    }

    /** Called under the lock. */
    private void backOff(long now) {
        pause(now, backoff);
        backoff = Math.min(backoff * 2, BACKOFF_MAX);
    }

    /** Drops the batch and logs at most once a minute. Called under the lock. */
    private void invalid(String why) {
        if (invalidLog.allow(clock.getAsLong())) {
            say("Fingerly agent sightings: " + why + "; the batch was dropped");
        }
    }

    /**
     * Puts a batch that was not answered back at the front, adding its
     * counts to newer repeats of the same requests. Called under the lock.
     */
    private void keep(List<Pending> batch) {
        LinkedHashMap<String, Pending> newer = pending;
        LinkedHashMap<String, Pending> merged = new LinkedHashMap<>();
        long bytes = 0;
        for (Pending item : batch) {
            Pending repeat = item.observation.signed ? null : newer.remove(item.slot);
            if (repeat != null) {
                pendingBytes -= repeat.observation.size;
                long count = Math.min(item.count + repeat.count, AgentsService.MAX_COUNT);
                merged.put(item.slot, new Pending(item.slot, item.observation, item.born, count));
                bytes += item.observation.size;
            } else {
                // Always fits: the cap counted this batch while it was in flight.
                merged.put(item.slot, item);
                bytes += item.observation.size;
            }
        }
        merged.putAll(newer);
        pending = merged;
        pendingBytes += bytes;
    }

    private void say(String line) {
        try {
            log.accept(line);
        } catch (RuntimeException ignored) {
            // A logging hook that throws changes nothing here.
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** A header value, or null when absent, empty, only whitespace or over {@link #MAX_HEADER_BYTES}. */
    private static String bounded(String value) {
        return value == null || value.isBlank() || utf8Length(value) > MAX_HEADER_BYTES ? null : value;
    }

    /** The UTF-8 length of a string, without encoding it. */
    static long utf8Length(String value) {
        if (value == null) {
            return 0;
        }
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /** The media type of a {@code Content-Type}, lowercased, without parameters. */
    private static String mediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int parameters = contentType.indexOf(';');
        String media = (parameters >= 0 ? contentType.substring(0, parameters) : contentType)
                .trim().toLowerCase(Locale.ROOT);
        return media.isEmpty() ? null : media;
    }

    @Override
    public String toString() {
        return "AgentSightings[cacheSize=" + cache.size() + ", dropped=" + dropped.get() + "]";
    }

    /** Builds an {@link AgentSightings}. */
    public static final class Builder {
        private FingerlyClient client;
        private FingerlyProxy.ClientIp clientIp;
        private int cacheCapacity = DEFAULT_CACHE_CAPACITY;
        private Set<String> contentTypes = new LinkedHashSet<>(DEFAULT_CONTENT_TYPES);
        private List<String> includePrefixes = List.of();
        private List<String> excludePrefixes = List.of();
        private Consumer<String> log;
        private Sender sender;
        private LongSupplier clock;
        private Supplier<Instant> wallClock;
        private boolean background = true;

        private Builder() {}

        /** The client whose secret key reports the requests. Required. */
        public Builder client(FingerlyClient client) {
            this.client = client;
            return this;
        }

        /**
         * Resolves the visitor's address, exactly as for {@link FingerlyProxy}:
         * one IPv4 or IPv6 literal from infrastructure you trust, never the
         * left-most {@code X-Forwarded-For}. A request it gives no address for
         * is not reported, and that is logged once. Required.
         */
        public Builder clientIp(FingerlyProxy.ClientIp clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        /**
         * How many answers to remember, at least 1; a value above
         * {@link #MAX_CACHE_CAPACITY} is lowered to it. Defaults to
         * {@link #DEFAULT_CACHE_CAPACITY}. Each costs a few hundred bytes.
         */
        public Builder cacheCapacity(int entries) {
            this.cacheCapacity = entries;
            return this;
        }

        /**
         * Replaces the response media types reported, {@link #DEFAULT_CONTENT_TYPES}
         * by default. Matched without regard to case or parameters.
         */
        public Builder contentTypes(Collection<String> types) {
            Set<String> collected = new LinkedHashSet<>();
            for (String type : Objects.requireNonNull(types, "types")) {
                String media = mediaType(type);
                if (media == null) {
                    throw new IllegalArgumentException("content types must not be empty");
                }
                collected.add(media);
            }
            this.contentTypes = collected;
            return this;
        }

        /** {@link #contentTypes(Collection)} from varargs. */
        public Builder contentTypes(String... types) {
            return contentTypes(Arrays.asList(types));
        }

        /**
         * Path prefixes reported whatever their content type, such as
         * {@code /docs/}. None by default.
         */
        public Builder includePathPrefixes(Collection<String> prefixes) {
            this.includePrefixes = prefixes(prefixes);
            return this;
        }

        /** {@link #includePathPrefixes(Collection)} from varargs. */
        public Builder includePathPrefixes(String... prefixes) {
            return includePathPrefixes(Arrays.asList(prefixes));
        }

        /**
         * Path prefixes never reported, such as {@code /admin/}; they win over
         * everything else. None by default.
         */
        public Builder excludePathPrefixes(Collection<String> prefixes) {
            this.excludePrefixes = prefixes(prefixes);
            return this;
        }

        /** {@link #excludePathPrefixes(Collection)} from varargs. */
        public Builder excludePathPrefixes(String... prefixes) {
            return excludePathPrefixes(Arrays.asList(prefixes));
        }

        /**
         * Receives the few lines this logs (a refused key, an SDK bug, a
         * client IP callback that returns nothing), none of which carries
         * request data. Defaults to the {@code System.Logger} named after this
         * class, at WARNING.
         */
        public Builder log(Consumer<String> log) {
            this.log = log;
            return this;
        }

        /** Replaces the API call, for tests. */
        Builder sender(Sender sender) {
            this.sender = sender;
            return this;
        }

        /** Replaces {@link System#nanoTime()}, for tests. */
        Builder clock(LongSupplier clock) {
            this.clock = clock;
            return this;
        }

        /** Replaces {@link Instant#now()}, for tests. */
        Builder wallClock(Supplier<Instant> wallClock) {
            this.wallClock = wallClock;
            return this;
        }

        /** Without the flusher thread, so a test drives {@code tick()} itself. */
        Builder manual() {
            this.background = false;
            return this;
        }

        private static List<String> prefixes(Collection<String> prefixes) {
            List<String> collected = new ArrayList<>();
            for (String prefix : Objects.requireNonNull(prefixes, "prefixes")) {
                if (prefix == null || prefix.isEmpty()) {
                    throw new IllegalArgumentException("path prefixes must not be empty");
                }
                collected.add(prefix);
            }
            return List.copyOf(collected);
        }

        /**
         * Builds it and starts its flusher thread.
         *
         * @throws IllegalArgumentException if the client or clientIp is
         *     missing, or cacheCapacity is below 1
         */
        public AgentSightings build() {
            return new AgentSightings(this);
        }
    }
}
