package io.fingerly.server.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fingerly.server.FingerlyProxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** The reactive adapter, driven through Spring's own mock exchange. */
class FingerlyProxyWebFilterTest {

    private static final String PROXY_KEY = "fly_px_development_test";
    private static final String PUBLIC_KEY = "fly_pk_development_test";
    private static final String PATH = "/k7x2qm4a";

    private static FingerlyProxy proxy(String upstream) {
        return FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path(PATH)
                .upstream(upstream)
                .clientIp(request -> request.header("CF-Connecting-IP"))
                .build();
    }

    private static FingerlyProxyWebFilter filter(String upstream) {
        // An immediate scheduler keeps the test deterministic; production uses
        // boundedElastic so the blocking forward leaves the event loop.
        return new FingerlyProxyWebFilter(proxy(upstream), Schedulers.immediate());
    }

    @Test
    void refusesAProxyWithoutAPath() {
        // A web filter sees every request; without a path it would take them all.
        FingerlyProxy unrouted = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .clientIp(request -> "203.0.113.5")
                .build();
        assertThrows(IllegalArgumentException.class, () -> new FingerlyProxyWebFilter(unrouted));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/orders", "/k7x2qm4a/admin"})
    void passesOtherPathsAlongWithTheBodyIntact(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path).body("{\"order\":1}"));
        AtomicReference<String> seenByApp = new AtomicReference<>();

        Mono<Void> result = filter("https://api.fingerly.io").filter(exchange, passed ->
                passed.getRequest().getBody()
                        .map(buffer -> buffer.toString(StandardCharsets.UTF_8))
                        .doOnNext(seenByApp::set)
                        .then());

        StepVerifier.create(result).verifyComplete();
        // Asking handle() first would have consumed this.
        assertEquals("{\"order\":1}", seenByApp.get());
    }

    @Test
    void refusesAWrongMethodOnAnOwnedPathItself() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get(PATH));
        AtomicReference<Boolean> reachedApp = new AtomicReference<>(false);

        StepVerifier.create(filter("https://api.fingerly.io").filter(exchange, passed -> {
            reachedApp.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertFalse(reachedApp.get(), "an owned path must not reach the application");
        assertEquals(405, exchange.getResponse().getRawStatusCode());
        assertEquals("POST", exchange.getResponse().getHeaders().getFirst("Allow"));
    }

    @Test
    void refusesAPublicKeyFromAnotherDeployment() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, PATH)
                        .header("X-API-Key", "fly_pk_production_test")
                        .body("{}"));

        StepVerifier.create(filter("https://api.fingerly.io")
                .filter(exchange, passed -> Mono.empty())).verifyComplete();

        assertEquals(401, exchange.getResponse().getRawStatusCode());
        assertEquals("no-store", exchange.getResponse().getHeaders().getFirst("Cache-Control"));
    }

    @Test
    void refusesABodyPastTheLimitWhileItStreams() {
        FingerlyProxy proxy = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path(PATH)
                .upstream("https://api.fingerly.io")
                .maxBodyBytes(8)
                .clientIp(request -> "203.0.113.5")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, PATH)
                        .header("X-API-Key", PUBLIC_KEY)
                        .body("a body well past the limit"));

        StepVerifier.create(new FingerlyProxyWebFilter(proxy, Schedulers.immediate())
                .filter(exchange, passed -> Mono.empty())).verifyComplete();

        assertEquals(413, exchange.getResponse().getRawStatusCode());
    }

    @Test
    void reportsAnUnreachableUpstreamAsBadGateway() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, PATH)
                        .header("X-API-Key", PUBLIC_KEY)
                        .header("CF-Connecting-IP", "203.0.113.5")
                        .body("{}"));

        StepVerifier.create(filter("http://127.0.0.1:1")
                .filter(exchange, passed -> Mono.empty())).verifyComplete();

        assertEquals(502, exchange.getResponse().getRawStatusCode());
    }

    @Test
    void answersItsPathWithATrailingSlash() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.POST, PATH + "/")
                        .header("X-API-Key", PUBLIC_KEY)
                        .header("CF-Connecting-IP", "203.0.113.5")
                        .body("{}"));
        AtomicReference<Boolean> reachedApp = new AtomicReference<>(false);

        StepVerifier.create(filter("http://127.0.0.1:1").filter(exchange, passed -> {
            reachedApp.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertFalse(reachedApp.get());
        assertTrue(exchange.getResponse().getRawStatusCode() == 502,
                "it tried to forward, so the path matched");
    }
}
