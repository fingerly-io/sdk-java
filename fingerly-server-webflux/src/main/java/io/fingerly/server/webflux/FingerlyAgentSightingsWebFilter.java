package io.fingerly.server.webflux;

import io.fingerly.server.AgentSighting;
import io.fingerly.server.AgentSightings;
import io.fingerly.server.ProxyRequest;
import java.util.Objects;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reports the requests your application served under reactive Spring, so AI
 * crawlers and fetchers that never run a page's JavaScript are seen.
 *
 * <p>Every decision belongs to {@link AgentSightings}; this class only reads
 * the request and your response. When the response's headers are about to be
 * written it checks their content type and the path, and holds an in-scope
 * request in memory. Nothing blocks and nothing waits on the network, so the
 * response is never delayed, and a streamed response is reported as soon as
 * its headers are known.
 *
 * <pre>{@code
 * @Bean
 * AgentSightings agentSightings(FingerlyClient fingerly) {
 *     return AgentSightings.builder()
 *         .client(fingerly)
 *         .clientIp(request -> request.header("CF-Connecting-IP"))
 *         .build();
 * }
 *
 * @Bean
 * FingerlyAgentSightingsWebFilter agentSightingsFilter(AgentSightings sightings) {
 *     return new FingerlyAgentSightingsWebFilter(sightings);
 * }
 * }</pre>
 */
public final class FingerlyAgentSightingsWebFilter implements WebFilter {

    private final AgentSightings sightings;

    /**
     * @param sightings the configured sightings; every option lives on it
     */
    public FingerlyAgentSightingsWebFilter(AgentSightings sightings) {
        this.sightings = Objects.requireNonNull(sightings, "sightings");
    }

    /**
     * What Fingerly last said about this request, from memory only: never
     * waits and never throws. Compare against the {@code VisitorKind}
     * constants; {@link AgentSighting#UNKNOWN} means "serve as usual".
     */
    public AgentSighting lookup(ServerWebExchange exchange) {
        try {
            return sightings.lookup(describe(exchange.getRequest()));
        } catch (RuntimeException failOpen) {
            return AgentSighting.UNKNOWN;
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            report(exchange.getRequest(), response);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    /** Holds the request when the response is in scope. Never throws. */
    private void report(ServerHttpRequest request, ServerHttpResponse response) {
        try {
            String contentType = response.getHeaders().getFirst("Content-Type");
            if (sightings.reports(request.getPath().value(), contentType)) {
                sightings.report(describe(request), contentType);
            }
        } catch (RuntimeException ignored) {
            // Reporting never affects the response.
        }
    }

    /**
     * The neutral request, with every header, since the client IP callback
     * may read any of them. The body is never read.
     */
    private static ProxyRequest describe(ServerHttpRequest request) {
        ProxyRequest.Builder builder = ProxyRequest.builder()
                .method(request.getMethod() == null ? "" : request.getMethod().name())
                .path(request.getPath().value())
                .remoteAddress(FingerlyProxyWebFilter.peer(request));
        request.getHeaders().forEach(builder::headers);
        return builder.build();
    }
}
