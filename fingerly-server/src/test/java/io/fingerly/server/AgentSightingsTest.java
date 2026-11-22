package io.fingerly.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The agent sightings component, driven by a fake clock and a sender whose
 * answers the test completes by hand, so every threshold and failure is
 * exact rather than timed.
 */
class AgentSightingsTest {

    private static final Path CONTRACT =
            Path.of(System.getProperty("fingerly.contract.dir", "../contract"));

    private static final String GPTBOT =
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; GPTBot/1.4; +https://openai.com/gptbot";
    private static final String BROWSER =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/141.0.0.0 Safari/537.36";

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    /** A sender whose calls the test answers. */
    static final class Calls implements AgentSightings.Sender {
        final List<List<AgentSightingReport>> sent = new ArrayList<>();
        final List<CompletableFuture<AgentSightingsResult>> answers = new ArrayList<>();

        @Override
        public synchronized CompletableFuture<AgentSightingsResult> send(List<AgentSightingReport> items) {
            sent.add(items);
            CompletableFuture<AgentSightingsResult> answer = new CompletableFuture<>();
            answers.add(answer);
            return answer;
        }

        int count() {
            return sent.size();
        }

        List<AgentSightingReport> last() {
            return sent.get(sent.size() - 1);
        }

        CompletableFuture<AgentSightingsResult> lastAnswer() {
            return answers.get(answers.size() - 1);
        }

        /** Answers the last call with one answer per item, each with this ttl and kind. */
        void answerAll(String rulesVersion, long ttl, String kind) {
            answer(rulesVersion, ttl, kind, last().size());
        }

        void answer(String rulesVersion, long ttl, String kind, int items) {
            StringBuilder body = new StringBuilder("{\"rules_version\":\"" + rulesVersion + "\",\"items\":[");
            for (int i = 0; i < items; i++) {
                body.append(i == 0 ? "" : ",").append("{\"ttl\":").append(ttl)
                        .append(",\"visitor_kind\":\"").append(kind).append("\"")
                        .append(VisitorKind.AI_BOT.equals(kind) ? ",\"agent\":\"GPTBot\"" : "")
                        .append("}");
            }
            body.append("]}");
            lastAnswer().complete(AgentsService.decode(body.toString().getBytes(StandardCharsets.UTF_8)));
        }

        void fail(Throwable failure) {
            lastAnswer().completeExceptionally(failure);
        }
    }

    private final AtomicLong now = new AtomicLong(1_000 * SECOND);
    private final AtomicReference<Instant> wall = new AtomicReference<>(Instant.parse("2026-11-21T12:00:00Z"));
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final Calls calls = new Calls();

    private AgentSightings.Builder builder() {
        return AgentSightings.builder()
                .clientIp(ProxyRequest::remoteAddress)
                .sender(calls)
                .clock(now::get)
                .wallClock(wall::get)
                .log(logs::add)
                .manual();
    }

    private AgentSightings sightings() {
        return builder().build();
    }

    private void advance(long nanos) {
        now.addAndGet(nanos);
    }

    private static ProxyRequest request(String userAgent, String ip) {
        return request(userAgent, ip, "/");
    }

    private static ProxyRequest request(String userAgent, String ip, String path) {
        ProxyRequest.Builder builder = ProxyRequest.builder().method("GET").path(path)
                .header("Host", "example.com").remoteAddress(ip);
        if (userAgent != null) {
            builder.header("User-Agent", userAgent);
        }
        return builder.build();
    }

    private static ProxyRequest signed(String ip) {
        return ProxyRequest.builder().method("GET").path("/").header("Host", "example.com")
                .header("User-Agent", BROWSER)
                .header("Signature", "sig1=:YWJj:")
                .header("Signature-Input", "sig1=(\"@authority\");created=1")
                .header("Signature-Agent", "\"https://chatgpt.com\"")
                .remoteAddress(ip).build();
    }

    private static void html(AgentSightings sightings, ProxyRequest request) {
        sightings.report(request, "text/html; charset=utf-8");
    }

    // ----- section 4.1: the key -----

    @TestFactory
    List<DynamicTest> reproducesEveryKeyVector() throws IOException {
        JsonNode vectors = Json.MAPPER.readTree(
                Files.readAllBytes(CONTRACT.resolve("agent-sightings-vectors.json")));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : vectors.path("vectors")) {
            tests.add(DynamicTest.dynamicTest(vector.path("name").asText(), () -> {
                String canonical = SightingKey.canonicalIp(vector.path("client_ip").asText());
                assertEquals(vector.path("canonical_ip").asText(), canonical);
                assertEquals(vector.path("key").asText(), SightingKey.of(
                        vector.path("user_agent").asText(), canonical,
                        vector.path("signature_agent").asText(), vector.path("signed").asBoolean()));

                // The same key from a request, as the component reads it.
                ProxyRequest.Builder request = ProxyRequest.builder().method("GET").path("/")
                        .header("User-Agent", vector.path("user_agent").asText())
                        .remoteAddress(vector.path("client_ip").asText());
                if (!vector.path("signature_agent").asText().isEmpty()) {
                    request.header("Signature-Agent", vector.path("signature_agent").asText());
                }
                if (vector.path("signed").asBoolean()) {
                    request.header("Signature", "sig1=:YWJj:").header("Signature-Input", "sig1=()");
                }
                AgentSightings.Observation observation = sightings().observe(request.build());
                assertNotNull(observation);
                assertEquals(vector.path("key").asText(), observation.key);
                assertEquals(vector.path("canonical_ip").asText(), observation.clientIp);
            }));
        }
        assertFalse(tests.isEmpty());
        return tests;
    }

    @Test
    void aUtf8HeaderDecodedAsOneCharPerByteIsReadAsTheBytesThatArrived() {
        // Servlet containers and Netty hand over header bytes as ISO-8859-1.
        String userAgent = "Mozilla/5.0 (compatible; Örnek/1.0)";
        String signatureAgent = "\"https://örnek.example\"";
        AgentSightings sightings = sightings();
        AgentSightings.Observation observation = sightings.observe(ProxyRequest.builder()
                .method("GET").path("/")
                .header("Host", latin1("örnek.example"))
                .header("User-Agent", latin1(userAgent))
                .header("Signature-Agent", latin1(signatureAgent))
                .remoteAddress("198.51.100.2").build());
        assertNotNull(observation);
        assertEquals(userAgent, observation.userAgent);
        assertEquals(signatureAgent, observation.signatureAgent);
        assertEquals("örnek.example", observation.host);
        assertEquals(SightingKey.of(userAgent, "198.51.100.2", signatureAgent, false), observation.key);

        // The 4096 byte limit counts the bytes that arrived, not the mojibake.
        String long3000Bytes = "ü".repeat(1500);
        assertNotNull(sightings.observe(request(latin1(long3000Bytes), "198.51.100.2")));

        // Text already decoded, or bytes that are not UTF-8, stay as they are.
        assertEquals("Örnek", sightings.observe(request("Örnek", "198.51.100.2")).userAgent);
        assertEquals("Bot/1.0 Ã", sightings.observe(request("Bot/1.0 Ã", "198.51.100.2")).userAgent);
        assertEquals("Ünï ☃", sightings.observe(request("Ünï ☃", "198.51.100.2")).userAgent);
    }

    @Test
    void theVectorUserAgentKeysAlikeHoweverItsBytesWereDecoded() {
        assertEquals("f9955cca177b63716e24d9e8b5d2f26b", sightings().observe(
                request(latin1("Mozilla/5.0 (compatible; Örnek/1.0)"), "198.51.100.2")).key);
    }

    private static String latin1(String text) {
        return new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    @Test
    void canonicalAddressesFollowRfc5952() {
        assertEquals("2001:db8::1:0:0:1", SightingKey.canonicalIp("2001:db8:0:0:1:0:0:1"));
        assertEquals("2001:db8:0:1:1:1:1:1", SightingKey.canonicalIp("2001:db8:0:1:1:1:1:1"));
        assertEquals("::", SightingKey.canonicalIp("0:0:0:0:0:0:0:0"));
        assertEquals("::1", SightingKey.canonicalIp("0:0:0:0:0:0:0:1"));
        assertEquals("1::", SightingKey.canonicalIp("1:0:0:0:0:0:0:0"));
        assertEquals("2001:db8::ff00:42:8329",
                SightingKey.canonicalIp("2001:0db8:0000:0000:0000:ff00:0042:8329"));
        assertEquals("192.0.2.1", SightingKey.canonicalIp("::FFFF:192.0.2.1"));
    }

    @Test
    void anythingButOneLiteralHasNoCanonicalForm() {
        assertNull(SightingKey.canonicalIp(null));
        assertNull(SightingKey.canonicalIp(""));
        assertNull(SightingKey.canonicalIp("203.0.113.7, 198.51.100.1"));
        assertNull(SightingKey.canonicalIp("203.0.113.7:443"));
        assertNull(SightingKey.canonicalIp("fe80::1%eth0"));
        assertNull(SightingKey.canonicalIp("example.com"));
        assertNull(SightingKey.canonicalIp("010.0.0.1"));
    }

    // ----- section 4.2 and 4.3: the cache -----

    @Test
    void theCacheIsABoundedLru() {
        AgentSightings.AnswerCache cache = new AgentSightings.AnswerCache(2);
        AgentSighting answer = AgentSighting.UNKNOWN;
        cache.put("a", answer, 60, 0);
        cache.put("b", answer, 60, 0);
        assertNotNull(cache.get("a", 1)); // a is now the most recent
        cache.put("c", answer, 60, 0);
        assertEquals(2, cache.size());
        assertNull(cache.get("b", 1), "the least recently used entry goes first");
        assertNotNull(cache.get("a", 1));
        assertNotNull(cache.get("c", 1));
    }

    @Test
    void capacityAboveTheMaximumIsClampedAndBelowOneRefused() {
        assertEquals(AgentSightings.DEFAULT_CACHE_CAPACITY, sightings().cacheCapacity());
        assertEquals(AgentSightings.MAX_CACHE_CAPACITY,
                builder().cacheCapacity(1_000_000).build().cacheCapacity());
        assertThrows(IllegalArgumentException.class, () -> builder().cacheCapacity(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> AgentSightings.builder().clientIp(ProxyRequest::remoteAddress).build());
        assertThrows(IllegalArgumentException.class,
                () -> AgentSightings.builder().sender(calls).build());
    }

    @Test
    void anAnswerIsServedUntilItsTtlPasses() {
        AgentSightings sightings = sightings();
        ProxyRequest gptBot = request(GPTBOT, "20.171.206.9");
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(gptBot));

        html(sightings, gptBot);
        sightings.flushNow();
        calls.answerAll("r1", 10, VisitorKind.AI_BOT);

        AgentSighting answer = sightings.lookup(gptBot);
        assertEquals(VisitorKind.AI_BOT, answer.getVisitorKind());
        assertEquals("GPTBot", answer.getAgent());
        // The mapped form of the same address is the same request.
        assertEquals(VisitorKind.AI_BOT,
                sightings.lookup(request(GPTBOT, "::ffff:20.171.206.9")).getVisitorKind());

        advance(10 * SECOND - 1);
        assertFalse(sightings.lookup(gptBot).isUnknown());
        advance(1);
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(gptBot));
        assertEquals(0, sightings.cacheSize(), "an expired entry is removed when found");
    }

    @Test
    void aTtlOfZeroIsNeverCached() {
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        calls.answerAll("r1", 0, VisitorKind.AI_BOT);
        assertEquals(0, sightings.cacheSize());
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(request(GPTBOT, "20.171.206.9")));
    }

    @Test
    void aTtlIsCappedAtADay() {
        AgentSightings sightings = sightings();
        ProxyRequest gptBot = request(GPTBOT, "20.171.206.9");
        html(sightings, gptBot);
        sightings.flushNow();
        calls.answerAll("r1", 30L * 86_400L, VisitorKind.AI_BOT);
        advance(86_400L * SECOND - 1);
        assertFalse(sightings.lookup(gptBot).isUnknown());
        advance(1);
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(gptBot), "remembered for one day at most");
    }

    @Test
    void aNewRulesVersionDropsEveryAnswer() {
        AgentSightings sightings = sightings();
        ProxyRequest first = request(GPTBOT, "20.171.206.9");
        ProxyRequest second = request(BROWSER, "203.0.113.7");
        html(sightings, first);
        sightings.flushNow();
        calls.answerAll("r1", 600, VisitorKind.AI_BOT);

        html(sightings, second);
        sightings.flushNow();
        calls.answerAll("r1", 600, VisitorKind.HUMAN);
        assertEquals(2, sightings.cacheSize(), "the same version keeps what it had");

        html(sightings, second);
        sightings.flushNow();
        calls.answerAll("r2", 600, VisitorKind.HUMAN);
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(first));
        assertEquals(VisitorKind.HUMAN, sightings.lookup(second).getVisitorKind());
        assertEquals(1, sightings.cacheSize());
    }

    @Test
    void lookupNeverRecordsAndNeverThrows() {
        AgentSightings sightings = sightings();
        sightings.lookup(request(GPTBOT, "20.171.206.9"));
        assertEquals(0, sightings.pendingSize());
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(null));
        AgentSightings broken = builder().clientIp(request -> {
            throw new IllegalStateException("broken");
        }).build();
        assertSame(AgentSighting.UNKNOWN, broken.lookup(request(GPTBOT, "20.171.206.9")));
    }

    // ----- section 5: buffering -----

    @Test
    void repeatsFoldIntoOneCountIncludingCacheHits() {
        AgentSightings sightings = sightings();
        ProxyRequest gptBot = request(GPTBOT, "20.171.206.9");
        String key = sightings.observe(gptBot).key;
        html(sightings, gptBot);
        html(sightings, gptBot);
        html(sightings, gptBot);
        assertEquals(1, sightings.pendingSize());
        assertEquals(3, sightings.pendingCount(key));

        sightings.flushNow();
        assertEquals(1, calls.last().size());
        assertEquals(3, calls.last().get(0).getCount());
        calls.answerAll("r1", 600, VisitorKind.AI_BOT);

        // Answered from the cache, and still counted for the console.
        html(sightings, gptBot);
        html(sightings, gptBot);
        assertFalse(sightings.lookup(gptBot).isUnknown());
        assertEquals(2, sightings.pendingCount(key));
    }

    @Test
    void theCountSaturatesAtTheServerMaximum() {
        AgentSightings sightings = sightings();
        ProxyRequest gptBot = request(GPTBOT, "20.171.206.9");
        String key = sightings.observe(gptBot).key;
        for (int i = 0; i < 1_000_005; i++) {
            html(sightings, gptBot);
        }
        assertEquals(AgentsService.MAX_COUNT, sightings.pendingCount(key));

        // A kept batch merged with newer repeats saturates too.
        sightings.flushNow();
        html(sightings, gptBot);
        calls.fail(new FingerlyApiException(503, null, null, null, null));
        assertEquals(AgentsService.MAX_COUNT, sightings.pendingCount(key));
        assertEquals(1, sightings.pendingSize());
    }

    @Test
    void signedRequestsAreNeverFolded() {
        AgentSightings sightings = sightings();
        html(sightings, signed("34.31.25.14"));
        html(sightings, signed("34.31.25.14"));
        assertEquals(2, sightings.pendingSize());

        sightings.flushNow();
        List<AgentSightingReport> sent = calls.last();
        assertEquals(2, sent.size());
        for (AgentSightingReport item : sent) {
            assertEquals(1, item.getCount());
            assertEquals("sig1=:YWJj:", item.getSignature());
            assertEquals("\"https://chatgpt.com\"", item.getSignatureAgent());
        }
        calls.answerAll("r1", 0, VisitorKind.AI_BOT);
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(signed("34.31.25.14")));
    }

    @Test
    void flushesAt200Items() {
        AgentSightings sightings = sightings();
        for (int i = 0; i < 199; i++) {
            html(sightings, request(GPTBOT + " " + i, "198.51.100.1"));
        }
        sightings.tick();
        assertEquals(0, calls.count(), "199 items and no age: nothing is due");
        html(sightings, request(GPTBOT + " 199", "198.51.100.1"));
        sightings.tick();
        assertEquals(1, calls.count());
        assertEquals(200, calls.last().size());
    }

    @Test
    void flushesAt64KibAndKeepsEachBatchUnderIt() {
        AgentSightings sightings = sightings();
        String big = "x".repeat(4000);
        for (int i = 0; i < 17; i++) {
            html(sightings, request(big + i, "198.51.100.1"));
        }
        sightings.tick();
        assertEquals(1, calls.count(), "17 items of about 4 KB pass 64 KiB");
        long bytes = 0;
        for (AgentSightingReport item : calls.last()) {
            bytes += AgentSightings.utf8Length(item.getUserAgent())
                    + AgentSightings.utf8Length(item.getClientIp())
                    + AgentSightings.utf8Length(item.getHost())
                    + AgentSightings.utf8Length(item.getMethod());
        }
        assertTrue(bytes <= AgentSightings.BATCH_BYTES, "a batch stays within 64 KiB: " + bytes);
        assertTrue(calls.last().size() < 17);
    }

    @Test
    void flushesTwoSecondsAfterTheOldestItem() {
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        advance(2 * SECOND - 1);
        sightings.tick();
        assertEquals(0, calls.count());
        advance(1);
        sightings.tick();
        assertEquals(1, calls.count());
    }

    @Test
    void atMostOneFlushIsInFlight() {
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        advance(3 * SECOND);
        sightings.tick();
        html(sightings, request(BROWSER, "203.0.113.7"));
        advance(3 * SECOND);
        sightings.tick();
        sightings.tick();
        assertEquals(1, calls.count());
        calls.answerAll("r1", 60, VisitorKind.AI_BOT);
        sightings.tick();
        assertEquals(2, calls.count());
    }

    @Test
    void aFullBufferDropsNewKeysAndCountsThem() {
        AgentSightings sightings = sightings();
        ProxyRequest first = request(GPTBOT + " 0", "198.51.100.1");
        for (int i = 0; i < AgentSightings.MAX_PENDING; i++) {
            html(sightings, request(GPTBOT + " " + i, "198.51.100.1"));
        }
        assertEquals(1000, sightings.pendingSize());
        html(sightings, request("new", "198.51.100.1"));
        assertEquals(1, sightings.getDroppedCount());
        assertEquals(1000, sightings.pendingSize());
        html(sightings, first);
        assertEquals(2, sightings.pendingCount(sightings.observe(first).key),
                "an existing key keeps counting");

        // The batch in flight still counts against the cap.
        sightings.tick();
        assertEquals(200, calls.last().size());
        assertEquals(800, sightings.pendingSize());
        html(sightings, request("another", "198.51.100.1"));
        assertEquals(2, sightings.getDroppedCount());

        // So a kept batch always fits back.
        calls.fail(new FingerlyApiException(503, null, null, null, null));
        assertEquals(1000, sightings.pendingSize());
        assertEquals(2, sightings.getDroppedCount());
    }

    // ----- section 6: answers and failures -----

    @Test
    void aSuccessStoresAnswersByPosition() {
        AgentSightings sightings = sightings();
        ProxyRequest gptBot = request(GPTBOT, "20.171.206.9");
        ProxyRequest browser = request(BROWSER, "203.0.113.7");
        html(sightings, gptBot);
        html(sightings, browser);
        sightings.flushNow();
        String body = "{\"rules_version\":\"r1\",\"items\":["
                + "{\"ttl\":900,\"visitor_kind\":\"ai_bot\",\"agent\":\"GPTBot\"},"
                + "{\"ttl\":3600,\"visitor_kind\":\"human\"}]}";
        calls.lastAnswer().complete(AgentsService.decode(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(VisitorKind.AI_BOT, sightings.lookup(gptBot).getVisitorKind());
        assertEquals(VisitorKind.HUMAN, sightings.lookup(browser).getVisitorKind());
        assertEquals(0, sightings.pendingSize());
    }

    @Test
    void anInvalidRequestDropsTheBatchAndLogsOncePerMinute() {
        AgentSightings sightings = sightings();
        for (int round = 0; round < 2; round++) {
            html(sightings, request(GPTBOT, "20.171.206.9"));
            sightings.flushNow();
            calls.fail(new FingerlyApiException(422, "invalid_request", null, null, null));
            assertEquals(0, sightings.pendingSize());
        }
        assertEquals(1, logs.size());
        advance(60 * SECOND);
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        calls.fail(new FingerlyApiException(422, "invalid_request", null, null, null));
        assertEquals(2, logs.size());
    }

    @Test
    void aRateLimitKeepsTheBatchUntilRetryAfter() {
        AgentSightings sightings = sightings();
        ProxyRequest gptBot = request(GPTBOT, "20.171.206.9");
        html(sightings, gptBot);
        html(sightings, gptBot);
        sightings.flushNow();
        calls.fail(new FingerlyApiException(429, "rate_limited", null, null, 30, null));
        String key = sightings.observe(gptBot).key;
        assertEquals(2, sightings.pendingCount(key), "the batch is kept");

        html(sightings, gptBot); // counting continues
        assertEquals(3, sightings.pendingCount(key));
        advance(29 * SECOND);
        sightings.tick();
        sightings.flushNow();
        assertEquals(1, calls.count(), "nothing is sent while the API asked to wait");
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(gptBot));

        advance(SECOND);
        sightings.tick();
        assertEquals(2, calls.count(), "the kept item is old, so it is due at once");
        assertEquals(3, calls.last().get(0).getCount());
    }

    @Test
    void aRetryAfterOfZeroOrInThePastPausesASecondAndAnUnreadableOneCountsAsMissing() {
        assertEquals(Integer.valueOf(0), FingerlyClient.retryAfter("0"));
        assertEquals(Integer.valueOf(0), FingerlyClient.retryAfter("Sun, 06 Nov 1994 08:49:37 GMT"));
        assertNull(FingerlyClient.retryAfter("-5"));
        assertNull(FingerlyClient.retryAfter("soon"));

        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        calls.fail(new FingerlyApiException(429, "rate_limited", null, null, 0, null));
        advance(SECOND - 1);
        sightings.flushNow();
        assertEquals(1, calls.count(), "a Retry-After of 0 still pauses a second");
        advance(1);
        sightings.flushNow();
        assertEquals(2, calls.count());
    }

    @Test
    void aDailyRateLimitWithoutRetryAfterWaitsForTheNextUtcDay() {
        wall.set(Instant.parse("2026-11-21T23:59:00Z"));
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        calls.fail(new FingerlyApiException(429, "rate_limited", null, null, null, "daily"));
        advance(59 * SECOND);
        sightings.flushNow();
        assertEquals(1, calls.count());
        advance(SECOND);
        sightings.flushNow();
        assertEquals(2, calls.count());
    }

    @Test
    void aRateLimitWithoutRetryAfterWaitsAMinute() {
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        calls.fail(new FingerlyApiException(429, "rate_limited", null, null, null, null));
        advance(59 * SECOND);
        sightings.flushNow();
        assertEquals(1, calls.count());
        advance(SECOND);
        sightings.flushNow();
        assertEquals(2, calls.count());
    }

    @Test
    void untilTheNextUtcDay() {
        assertEquals(Duration.ofMinutes(1),
                AgentSightings.untilNextUtcDay(Instant.parse("2026-11-21T23:59:00Z")));
        assertEquals(Duration.ofDays(1),
                AgentSightings.untilNextUtcDay(Instant.parse("2026-11-22T00:00:00Z")));
    }

    @Test
    void aRefusedKeyDropsThePausesAMinuteAndLogsOncePerMinute() {
        for (int status : new int[] {401, 403}) {
            logs.clear();
            AgentSightings sightings = sightings();
            int before = calls.count();
            html(sightings, request(GPTBOT, "20.171.206.9"));
            sightings.flushNow();
            calls.fail(new FingerlyApiException(status, null, null, null, null));
            assertEquals(0, sightings.pendingSize(), "the batch is dropped");
            assertEquals(1, logs.size());

            html(sightings, request(GPTBOT, "20.171.206.9"));
            advance(59 * SECOND);
            sightings.flushNow();
            assertEquals(before + 1, calls.count(), "paused for 60 seconds");
            advance(SECOND);
            sightings.flushNow();
            assertEquals(before + 2, calls.count());
            calls.fail(new FingerlyApiException(status, null, null, null, null));
            assertEquals(2, logs.size(), "a minute later it is logged again");
        }
    }

    @Test
    void unavailableAndTransportErrorsKeepTheBatchAndBackOff() {
        List<Throwable> failures = List.of(
                new FingerlyApiException(503, null, null, null, null),
                new FingerlyConnectionException("refused", new IOException("refused")),
                new FingerlyTimeoutException("connect", new HttpConnectTimeoutException("connect")));
        for (Throwable failure : failures) {
            AgentSightings sightings = sightings();
            int before = calls.count();
            html(sightings, request(GPTBOT, "20.171.206.9"));
            sightings.flushNow();
            calls.fail(failure);
            assertEquals(1, sightings.pendingSize(), failure + " keeps the batch");

            // 1 s, then 2 s, then 4 s.
            long[] waits = {1, 2, 4};
            for (int i = 0; i < waits.length; i++) {
                advance(waits[i] * SECOND - 1);
                sightings.flushNow();
                assertEquals(before + 1 + i, calls.count());
                advance(1);
                sightings.flushNow();
                assertEquals(before + 2 + i, calls.count());
                calls.fail(failure);
            }
            // A success resets the backoff.
            advance(8 * SECOND);
            sightings.flushNow();
            calls.answerAll("r1", 60, VisitorKind.HUMAN);
            html(sightings, request(BROWSER, "203.0.113.7"));
            sightings.flushNow();
            calls.fail(failure);
            advance(SECOND);
            int sent = calls.count();
            sightings.flushNow();
            assertEquals(sent + 1, calls.count(), "back to a 1 second wait");
            calls.answerAll("r1", 60, VisitorKind.HUMAN);
        }
    }

    @Test
    void theBackoffIsCappedAtAMinute() {
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        for (int i = 0; i < 8; i++) {
            calls.fail(new FingerlyApiException(503, null, null, null, null));
            advance(64 * SECOND);
            sightings.flushNow();
        }
        calls.fail(new FingerlyApiException(503, null, null, null, null));
        int sent = calls.count();
        advance(60 * SECOND);
        sightings.flushNow();
        assertEquals(sent + 1, calls.count());
    }

    @Test
    void aTimeoutAfterSendingDropsTheBatch() {
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        calls.fail(new FingerlyTimeoutException("Fingerly request timed out", null));
        assertEquals(0, sightings.pendingSize());
        // Not a pause: the next request is sent at once.
        html(sightings, request(BROWSER, "203.0.113.7"));
        sightings.flushNow();
        assertEquals(2, calls.count());
    }

    @Test
    void aConnectionLostAfterTheRequestWasWrittenDropsTheBatch() {
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        calls.fail(new FingerlyConnectionException("reset", new IOException("reset"), true));
        assertEquals(0, sightings.pendingSize(), "the API may have counted it");
        // Not a backoff: the next request is sent at once.
        html(sightings, request(BROWSER, "203.0.113.7"));
        sightings.flushNow();
        assertEquals(2, calls.count());
    }

    @Test
    void theClientTellsWhetherTheRequestWasWrittenBeforeTheConnectionFailed() throws Exception {
        List<AgentSightingReport> items =
                List.of(AgentSightingReport.builder().clientIp("192.0.2.1").build());
        // Closed once the whole request was read, and cut short mid-answer.
        for (String answer : List.of("", "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: 100\r\n\r\n{\"rules_version\"")) {
            try (RawApi api = new RawApi(answer)) {
                Throwable failure = assertThrows(Exception.class,
                        () -> api.client().agents().reportSightingsAsync(items, Duration.ofSeconds(5)).join())
                        .getCause();
                assertTrue(failure instanceof FingerlyConnectionException, String.valueOf(failure));
                assertTrue(((FingerlyConnectionException) failure).requestWritten(), answer);
            }
        }
        // Refused: nothing was written.
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = probe.getLocalPort();
        }
        FingerlyClient refused = FingerlyClient.builder().secretKey(Fixtures.SECRET_KEY)
                .allowInsecureLoopback(true).endpoint("http://127.0.0.1:" + closedPort).build();
        Throwable failure = assertThrows(Exception.class,
                () -> refused.agents().reportSightingsAsync(items, Duration.ofSeconds(5)).join())
                .getCause();
        assertTrue(failure instanceof FingerlyConnectionException, String.valueOf(failure));
        assertFalse(((FingerlyConnectionException) failure).requestWritten());
    }

    /** A raw socket API on 127.0.0.1 that reads each request in full and answers fixed bytes. */
    private static final class RawApi implements AutoCloseable {
        private final ServerSocket socket;

        RawApi(String answer) throws IOException {
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            Thread worker = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        InputStream in = client.getInputStream();
                        StringBuilder head = new StringBuilder();
                        int b;
                        while (!head.toString().endsWith("\r\n\r\n") && (b = in.read()) != -1) {
                            head.append((char) b);
                        }
                        for (String line : head.toString().split("\r\n")) {
                            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                                in.readNBytes(Integer.parseInt(line.substring(15).trim()));
                            }
                        }
                        OutputStream out = client.getOutputStream();
                        out.write(answer.getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        FingerlyClient client() {
            return FingerlyClient.builder().secretKey(Fixtures.SECRET_KEY).allowInsecureLoopback(true)
                    .endpoint("http://127.0.0.1:" + socket.getLocalPort()).build();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void everyOtherStatusDropsTheBatchAndLogs() {
        for (int status : new int[] {400, 404, 413, 500, 502, 504}) {
            logs.clear();
            AgentSightings sightings = sightings();
            html(sightings, request(GPTBOT, "20.171.206.9"));
            sightings.flushNow();
            calls.fail(new FingerlyApiException(status, null, null, null, null));
            assertEquals(0, sightings.pendingSize(), status + " drops the batch");
            assertEquals(1, logs.size());
            html(sightings, request(GPTBOT, "20.171.206.9"));
            int sent = calls.count();
            sightings.flushNow();
            assertEquals(sent + 1, calls.count(), status + " does not pause");
            calls.answerAll("r1", 60, VisitorKind.HUMAN);
        }
    }

    @Test
    void aMismatchedOrUndecodableAnswerDropsTheBatch() {
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        html(sightings, request(BROWSER, "203.0.113.7"));
        sightings.flushNow();
        calls.answer("r1", 60, VisitorKind.HUMAN, 1);
        assertEquals(0, sightings.pendingSize());
        assertEquals(0, sightings.cacheSize());
        assertEquals(1, logs.size());

        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        calls.fail(new FingerlyException("Fingerly API returned a body that is not JSON"));
        assertEquals(0, sightings.pendingSize());
    }

    @Test
    void lookupKeepsAnsweringThroughFailures() {
        AgentSightings sightings = sightings();
        ProxyRequest gptBot = request(GPTBOT, "20.171.206.9");
        html(sightings, gptBot);
        sightings.flushNow();
        calls.answerAll("r1", 600, VisitorKind.AI_BOT);
        html(sightings, gptBot);
        sightings.flushNow();
        calls.fail(new FingerlyApiException(503, null, null, null, null));
        assertEquals(VisitorKind.AI_BOT, sightings.lookup(gptBot).getVisitorKind());
    }

    @Test
    void flushSendsEveryBatchAndStopsAtAPause() {
        AgentSightings sightings = builder().sender(items -> {
            calls.sent.add(items);
            if (calls.sent.size() == 3) {
                return CompletableFuture.failedFuture(new FingerlyApiException(503, null, null, null, null));
            }
            StringBuilder body = new StringBuilder("{\"rules_version\":\"r\",\"items\":[");
            for (int i = 0; i < items.size(); i++) {
                body.append(i == 0 ? "" : ",").append("{\"ttl\":60,\"visitor_kind\":\"human\"}");
            }
            return CompletableFuture.completedFuture(AgentsService.decode(
                    body.append("]}").toString().getBytes(StandardCharsets.UTF_8)));
        }).build();
        for (int i = 0; i < 650; i++) {
            html(sightings, request(BROWSER + " " + i, "203.0.113.7"));
        }
        sightings.flush();
        assertEquals(3, calls.sent.size(), "two answered, the third begins a backoff");
        sightings.flush();
        assertEquals(3, calls.sent.size(), "an explicit flush respects the backoff");
        assertEquals(250, sightings.pendingSize());
        assertEquals(400, sightings.cacheSize());
    }

    @Test
    void flushWaitsAtMostSixSecondsForAFlushInFlightAndNeverThrows() {
        assertEquals(Duration.ofSeconds(6), AgentSightings.FLUSH_WAIT);
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow(); // never answered
        long started = System.nanoTime();
        sightings.flush();
        long waited = System.nanoTime() - started;
        assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(5_900), "waited " + waited);
        assertTrue(waited < TimeUnit.SECONDS.toNanos(9), "waited " + waited);
    }

    @Test
    void closeFlushesAndThenIgnoresReports() {
        AgentSightings sightings = builder().sender(items -> {
            calls.sent.add(items);
            return CompletableFuture.completedFuture(AgentsService.decode(
                    "{\"rules_version\":\"r\",\"items\":[{\"ttl\":60,\"visitor_kind\":\"human\"}]}"
                            .getBytes(StandardCharsets.UTF_8)));
        }).build();
        ProxyRequest browser = request(BROWSER, "203.0.113.7");
        html(sightings, browser);
        sightings.close();
        assertEquals(1, calls.sent.size());
        html(sightings, browser);
        assertEquals(0, sightings.pendingSize());
        assertEquals(VisitorKind.HUMAN, sightings.lookup(browser).getVisitorKind());
        sightings.close(); // twice is fine
    }

    @Test
    void concurrentClosesFlushOnce() throws Exception {
        AgentSightings sightings = builder().sender(items -> {
            synchronized (calls) {
                calls.sent.add(items);
            }
            return CompletableFuture.supplyAsync(() -> AgentsService.decode(
                    "{\"rules_version\":\"r\",\"items\":[{\"ttl\":60,\"visitor_kind\":\"human\"}]}"
                            .getBytes(StandardCharsets.UTF_8)),
                    CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
        }).build();
        html(sightings, request(BROWSER, "203.0.113.7"));
        List<Thread> closers = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 8; i++) {
            Thread closer = new Thread(() -> {
                try {
                    sightings.close();
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            closers.add(closer);
            closer.start();
        }
        for (Thread closer : closers) {
            closer.join(10_000);
        }
        sightings.flush(); // waits for the one flush the first close started
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(1, calls.sent.size());
    }

    // ----- callbacks and failures that must never reach the host -----

    @Test
    void aCheckedExceptionFromTheClientIpCallbackFailsOpen() {
        // Kotlin and Lombok code can throw a checked exception through a Java
        // functional interface; it must not escape into the request.
        AgentSightings sightings = builder().clientIp(request -> {
            throw Fixtures.sneaky(new IOException("no such header"));
        }).build();
        ProxyRequest gptBot = request(GPTBOT, "20.171.206.9");
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(gptBot));
        html(sightings, gptBot);
        assertEquals(0, sightings.pendingSize());
        assertEquals(1, logs.size(), logs.toString());
    }

    @Test
    void aCheckedExceptionFromTheLogHookLeavesTheFlusherWorking() {
        AgentSightings sightings = builder().log(line -> {
            throw Fixtures.sneaky(new IOException("log sink closed"));
        }).build();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        // A refusal is logged, and the hook throws while the flush ends.
        calls.fail(new FingerlyApiException(422, "invalid_request", null, null, null));
        html(sightings, request(BROWSER, "203.0.113.7"));
        sightings.flushNow();
        assertEquals(2, calls.count(), "the next flush starts: the one before it ended");
    }

    @Test
    void anErrorWhileSendingEndsTheFlushAndTheNextOneStillRuns() {
        AtomicLong sends = new AtomicLong();
        AgentSightings sightings = builder().sender(items -> {
            if (sends.incrementAndGet() == 1) {
                throw new StackOverflowError("deep");
            }
            return calls.send(items);
        }).build();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        html(sightings, request(BROWSER, "203.0.113.7"));
        sightings.flushNow();
        assertEquals(2, sends.get());
        assertEquals(1, calls.count());
    }

    @Test
    void aTickSurvivesAnError() {
        AtomicReference<Error> bomb = new AtomicReference<>();
        AgentSightings sightings = builder().clock(() -> {
            Error error = bomb.get();
            if (error != null) {
                throw error;
            }
            return now.get();
        }).build();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        bomb.set(new AssertionError("broken clock"));
        sightings.tick(); // an executor never runs a task again once it has thrown
        bomb.set(null);
        advance(2 * SECOND);
        sightings.tick();
        assertEquals(1, calls.count());
    }

    @Test
    void aRetryAfterOfYearsPausesAtMostADay() {
        assertEquals(Duration.ofDays(1), AgentSightings.MAX_RATE_LIMIT_PAUSE);
        AgentSightings sightings = sightings();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        sightings.flushNow();
        calls.fail(new FingerlyApiException(429, "rate_limited", null, null, 999_999_999, null));
        advance(TimeUnit.DAYS.toNanos(1) - 1);
        sightings.flushNow();
        assertEquals(1, calls.count());
        advance(1);
        sightings.flushNow();
        assertEquals(2, calls.count(), "reporting resumes after a day");
    }

    // ----- section 3: what is sent -----

    @Test
    void sendsOnlyTheContractFields() {
        AgentSightings sightings = sightings();
        html(sightings, ProxyRequest.builder().method("get").path("/secret?token=abc")
                .header("Host", "Example.COM:8443")
                .header("User-Agent", GPTBOT)
                .header("Cookie", "session=1")
                .header("Authorization", "Bearer x")
                .remoteAddress("2001:DB8::1").build());
        sightings.flushNow();
        AgentSightingReport item = calls.last().get(0);
        assertEquals(GPTBOT, item.getUserAgent());
        assertEquals("2001:db8::1", item.getClientIp(), "the canonical form is sent");
        assertEquals("Example.COM:8443", item.getHost(), "the host as received");
        assertEquals("get", item.getMethod(), "the method as received");
        assertEquals(1, item.getCount());
        assertNull(item.getSignature());
        assertNull(item.getSignatureInput());
        assertNull(item.getSignatureAgent());
    }

    @Test
    void anOversizeUserAgentIsNotReported() {
        AgentSightings sightings = sightings();
        ProxyRequest huge = request("a".repeat(4097), "20.171.206.9");
        html(sightings, huge);
        assertEquals(0, sightings.pendingSize());
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(huge));
        // Counted in UTF-8 bytes, not characters.
        html(sightings, request("Ö".repeat(2049), "20.171.206.9"));
        assertEquals(0, sightings.pendingSize());
        html(sightings, request("a".repeat(4096), "20.171.206.9"));
        assertEquals(1, sightings.pendingSize());
    }

    @Test
    void anOversizeSignatureHeaderIsLeftOutAndNotSigned() {
        AgentSightings sightings = sightings();
        ProxyRequest request = ProxyRequest.builder().method("GET").path("/")
                .header("User-Agent", BROWSER)
                .header("Signature", "s".repeat(4097))
                .header("Signature-Input", "sig1=()")
                .header("Signature-Agent", "a".repeat(4097))
                .remoteAddress("34.31.25.14").build();
        AgentSightings.Observation observation = sightings.observe(request);
        assertFalse(observation.signed);
        assertEquals(SightingKey.of(BROWSER, "34.31.25.14", "", false), observation.key);
        html(sightings, request);
        html(sightings, request);
        assertEquals(1, sightings.pendingSize(), "no longer signed, so folded");
        sightings.flushNow();
        AgentSightingReport item = calls.last().get(0);
        assertNull(item.getSignature());
        assertNull(item.getSignatureAgent());
        assertEquals("sig1=()", item.getSignatureInput());
    }

    @Test
    void anEmptyOrBlankSignatureHeaderIsAbsent() {
        AgentSightings sightings = sightings();
        ProxyRequest request = ProxyRequest.builder().method("GET").path("/")
                .header("User-Agent", BROWSER)
                .header("Signature", "")
                .header("Signature-Input", " \t ")
                .header("Signature-Agent", "  ")
                .remoteAddress("34.31.25.14").build();
        AgentSightings.Observation observation = sightings.observe(request);
        assertFalse(observation.signed);
        assertEquals(SightingKey.of(BROWSER, "34.31.25.14", "", false), observation.key);
        html(sightings, request);
        sightings.flushNow();
        AgentSightingReport item = calls.last().get(0);
        assertNull(item.getSignature());
        assertNull(item.getSignatureInput());
        assertNull(item.getSignatureAgent());
    }

    @Test
    void anAbsentUserAgentIsReportedEmpty() {
        AgentSightings sightings = sightings();
        html(sightings, request(null, "198.51.100.1"));
        sightings.flushNow();
        assertEquals("", calls.last().get(0).getUserAgent());
    }

    @Test
    void anUnusableClientAddressIsNotReportedAndLoggedOncePerMinute() {
        AgentSightings sightings = builder()
                .clientIp(request -> request.header("X-Forwarded-For"))
                .build();
        ProxyRequest listed = ProxyRequest.builder().method("GET").path("/")
                .header("User-Agent", GPTBOT)
                .header("X-Forwarded-For", "203.0.113.7, 198.51.100.1").build();
        html(sightings, listed);
        html(sightings, listed);
        assertEquals(0, sightings.pendingSize());
        assertSame(AgentSighting.UNKNOWN, sightings.lookup(listed));
        assertEquals(1, logs.size());
        advance(60 * SECOND);
        html(sightings, listed);
        assertEquals(2, logs.size());
    }

    @Test
    void logsNeverCarryRequestFields() {
        AgentSightings sightings = builder().clientIp(request -> "not an address").build();
        html(sightings, request(GPTBOT, "20.171.206.9"));
        AgentSightings other = sightings();
        html(other, request(GPTBOT, "20.171.206.9"));
        other.flushNow();
        calls.fail(new FingerlyApiException(422, "invalid_request", null, null, null));
        html(other, request(GPTBOT, "20.171.206.9"));
        other.flushNow();
        calls.fail(new FingerlyApiException(401, null, null, null, null));
        assertEquals(3, logs.size());
        for (String line : logs) {
            assertFalse(line.contains("GPTBot"), line);
            assertFalse(line.contains("20.171.206.9"), line);
            assertFalse(line.contains("example.com"), line);
        }
    }

    // ----- section 2: which responses are reported -----

    @Test
    void reportsHtmlAndTheWellKnownPathsByDefault() {
        AgentSightings sightings = sightings();
        assertTrue(sightings.reports("/", "text/html"));
        assertTrue(sightings.reports("/page", "TEXT/HTML; charset=utf-8"));
        assertTrue(sightings.reports("/page", " application/xhtml+xml "));
        assertTrue(sightings.reports("/robots.txt", "text/plain"));
        assertTrue(sightings.reports("/llms.txt", null));
        assertTrue(sightings.reports("/llms-full.txt", "text/markdown"));
        assertTrue(sightings.reports("/robots.txt?x=1", "text/plain"), "the query is ignored");
        assertFalse(sightings.reports("/ROBOTS.TXT", "text/plain"), "exact and case-sensitive");
        assertFalse(sightings.reports("/app.js", "application/javascript"));
        assertFalse(sightings.reports("/api/items", "application/json"));
        assertFalse(sightings.reports("/health", null));
        assertFalse(sightings.reports("/page", "text/htmlx"));
    }

    @Test
    void scopeIsConfigurableByPathAndContentType() {
        AgentSightings sightings = builder()
                .contentTypes("application/json")
                .includePathPrefixes("/feeds/")
                .excludePathPrefixes("/admin", "/robots.txt")
                .build();
        assertTrue(sightings.reports("/api", "application/json; charset=utf-8"));
        assertFalse(sightings.reports("/", "text/html"), "the list replaces the default");
        assertTrue(sightings.reports("/feeds/rss", "application/rss+xml"));
        assertFalse(sightings.reports("/admin/users", "application/json"));
        assertFalse(sightings.reports("/robots.txt", "text/plain"), "exclusion wins over the fixed paths");
        assertTrue(sightings.reports("/llms.txt", "text/plain"));
        assertThrows(IllegalArgumentException.class, () -> builder().contentTypes(" ").build());
        assertThrows(IllegalArgumentException.class, () -> builder().includePathPrefixes("").build());
    }

    @Test
    void anOutOfScopeResponseIsNotRecorded() {
        AgentSightings sightings = sightings();
        sightings.report(request(GPTBOT, "20.171.206.9", "/app.js"), "application/javascript");
        sightings.report(request(GPTBOT, "20.171.206.9", "/robots.txt"), "text/plain");
        assertEquals(1, sightings.pendingSize());
    }

    // ----- section 7: the background flusher -----

    @Test
    void theFlusherSendsOnItsOwnDaemonThread() throws Exception {
        String answer = "{\"rules_version\":\"r1\",\"items\":[{\"ttl\":900,\"visitor_kind\":\"ai_bot\","
                + "\"agent\":\"GPTBot\"}]}";
        try (FakeApi api = FakeApi.json(200, answer)) {
            FingerlyClient client = FingerlyClient.builder().secretKey(Fixtures.SECRET_KEY)
                    .allowInsecureLoopback(true).endpoint(api.url()).build();
            try (AgentSightings sightings = AgentSightings.builder().client(client)
                    .clientIp(ProxyRequest::remoteAddress).build()) {
                ProxyRequest gptBot = request(GPTBOT, "20.171.206.9");
                long started = System.nanoTime();
                html(sightings, gptBot);
                assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(100),
                        "reporting never waits on the network");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                while (sightings.lookup(gptBot).isUnknown() && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertEquals(VisitorKind.AI_BOT, sightings.lookup(gptBot).getVisitorKind());
                assertEquals(1, api.callCount());
                JsonNode sent = Json.MAPPER.readTree(api.first().body).path("items").path(0);
                assertEquals(1, sent.path("count").asLong(), "count is sent even when 1");
                assertEquals("/api/v1/agents/sightings", api.first().path);
            }
        }
    }

    @Test
    void theFlusherThreadIsADaemon() throws Exception {
        AtomicReference<Thread> seen = new AtomicReference<>();
        try (AgentSightings sightings = AgentSightings.builder()
                .clientIp(ProxyRequest::remoteAddress)
                .sender(items -> {
                    seen.set(Thread.currentThread());
                    return CompletableFuture.completedFuture(AgentsService.decode(
                            "{\"rules_version\":\"r\",\"items\":[{\"ttl\":60,\"visitor_kind\":\"human\"}]}"
                                    .getBytes(StandardCharsets.UTF_8)));
                })
                .build()) {
            html(sightings, request(GPTBOT, "20.171.206.9"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (seen.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertNotNull(seen.get(), "the flusher sent after two seconds");
            assertTrue(seen.get().isDaemon());
            assertEquals("fingerly-agent-sightings", seen.get().getName());
        }
    }

    @Test
    void oneFlushHasAFiveSecondDeadline() throws IOException {
        assertEquals(Duration.ofSeconds(5), AgentSightings.DEADLINE);
        try (FakeApi api = FakeApi.start(exchange -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            FakeApi.respond(exchange, 200, "{}");
        })) {
            FingerlyClient client = FingerlyClient.builder().secretKey(Fixtures.SECRET_KEY)
                    .allowInsecureLoopback(true).endpoint(api.url()).build();
            CompletableFuture<AgentSightingsResult> pending = client.agents().reportSightingsAsync(
                    List.of(AgentSightingReport.builder().clientIp("192.0.2.1").build()),
                    Duration.ofMillis(200));
            Throwable failure = assertThrows(Exception.class, pending::join).getCause();
            assertTrue(failure instanceof FingerlyTimeoutException, String.valueOf(failure));
        }
    }
}
