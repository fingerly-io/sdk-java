package io.fingerly.server.webflux;

import io.fingerly.server.FingerlyProxy;
import io.fingerly.server.ProxyRequest;
import io.fingerly.server.ProxyResponse;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Serves the browser SDK's same-origin requests under reactive Spring.
 *
 * <p>Compiled against Spring 5.3, whose {@code org.springframework.web.server}
 * types are unchanged in 6.x, so one artifact serves Spring Boot 2 and 3.
 *
 * <p>Every decision belongs to {@link FingerlyProxy}. This class only reads a
 * request and writes a response, so the reactive stack behaves exactly as the
 * servlet ones do.
 *
 * <p>A web filter sees every request, so the proxy must be given the path the
 * dashboard issued with {@link FingerlyProxy.Builder#path path}. That path is
 * answered and every other request passes through to the application
 * untouched.
 *
 * <pre>{@code
 * @Bean
 * FingerlyProxyWebFilter fingerlyProxy() {
 *     return new FingerlyProxyWebFilter(FingerlyProxy.builder()
 *         .proxyKey(System.getenv("FINGERLY_PROXY_KEY"))
 *         .path("/k7x2qm4a")
 *         .clientIp(request -> request.header("CF-Connecting-IP"))
 *         .build());
 * }
 * }</pre>
 *
 * <p><strong>The forward is blocking.</strong> {@link FingerlyProxy} calls
 * Fingerly with a synchronous HTTP client, which must never run on an event
 * loop, so this filter moves that call to {@link Schedulers#boundedElastic()}.
 * Supply your own scheduler if you would rather it shared a pool you control.
 * The body is read reactively either way; only the upstream call is offloaded.
 */
public final class FingerlyProxyWebFilter implements WebFilter {

    private final FingerlyProxy proxy;
    private final Scheduler scheduler;

    /**
     * @param proxy the configured proxy; every option lives on it
     * @throws IllegalArgumentException if the proxy has no path
     */
    public FingerlyProxyWebFilter(FingerlyProxy proxy) {
        this(proxy, Schedulers.boundedElastic());
    }

    /**
     * @param proxy the configured proxy
     * @param scheduler where the blocking upstream call runs. It must not be
     *     an event-loop scheduler such as {@link Schedulers#parallel()}.
     * @throws IllegalArgumentException if the proxy has no path
     */
    public FingerlyProxyWebFilter(FingerlyProxy proxy, Scheduler scheduler) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        // Without a path the proxy would answer every request the application
        // serves, since nothing routes to a web filter.
        if (proxy.path().isEmpty()) {
            throw new IllegalArgumentException(
                    "FingerlyProxyWebFilter needs a proxy built with path(...)");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // Ask before reading: a route this proxy does not serve must reach the
        // application with its body intact.
        if (!proxy.handles(request.getPath().value())) {
            return chain.filter(exchange);
        }

        return readBody(request)
                .flatMap(body -> Mono
                        .fromCallable(() -> proxy.handle(toProxyRequest(request, body)))
                        .subscribeOn(scheduler))
                .onErrorResume(DataBufferLimitException.class,
                        ignored -> Mono.just(proxy.payloadTooLarge()))
                .flatMap(result -> write(exchange.getResponse(), result));
    }

    /**
     * Collects the request body, refusing anything past the proxy's limit
     * while it streams rather than after it is buffered.
     */
    private Mono<byte[]> readBody(ServerHttpRequest request) {
        Publisher<DataBuffer> body = request.getBody();
        int limit = (int) Math.min(proxy.maxBodyBytes(), Integer.MAX_VALUE);
        return DataBufferUtils.join(body, limit)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0]);
    }

    /** Builds the neutral request from the reactive one. */
    private static ProxyRequest toProxyRequest(ServerHttpRequest request, byte[] body) {
        ProxyRequest.Builder builder = ProxyRequest.builder()
                .method(request.getMethod() == null ? "" : request.getMethod().name())
                .path(request.getPath().value())
                .remoteAddress(peer(request))
                .body(body);
        request.getHeaders().forEach(builder::headers);
        return builder.build();
    }

    /** The connection's peer, without DNS: getHostAddress never resolves. */
    private static String peer(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        return remote.getAddress().getHostAddress();
    }

    private static Mono<Void> write(ServerHttpResponse response, ProxyResponse result) {
        response.setRawStatusCode(result.status());
        for (Map.Entry<String, String> header : result.headers()) {
            response.getHeaders().add(header.getKey(), header.getValue());
        }
        byte[] body = result.body();
        if (body.length == 0) {
            return response.setComplete();
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
