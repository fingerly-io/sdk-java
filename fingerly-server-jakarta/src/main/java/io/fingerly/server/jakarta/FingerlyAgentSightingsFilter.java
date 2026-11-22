package io.fingerly.server.jakarta;

import io.fingerly.server.AgentSighting;
import io.fingerly.server.AgentSightings;
import io.fingerly.server.ProxyRequest;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;

/**
 * Reports the requests your application served under Jakarta Servlet, so AI
 * crawlers and fetchers that never run a page's JavaScript are seen.
 *
 * <p>For Spring Boot 3, Tomcat 10 and newer, Jetty 11 and newer, and anything
 * else on {@code jakarta.servlet}. Every decision belongs to
 * {@link AgentSightings}; this class only reads the request and your response.
 *
 * <p>Map it to every path. After your application has produced the response,
 * it checks the response's content type and the path, and holds an in-scope
 * request in memory; the report is sent later, off the request thread, so the
 * response is never delayed. An asynchronous request is reported once, when
 * its last async cycle completes, whichever dispatcher types the filter is
 * mapped to. A request whose chain throws is not reported.
 *
 * <pre>{@code
 * @Bean
 * AgentSightings agentSightings(FingerlyClient fingerly) {
 *     return AgentSightings.builder()
 *         .client(fingerly)
 *         // Your CDN's header when it is there, otherwise the TCP peer.
 *         .clientIp(request -> request.header("CF-Connecting-IP") != null
 *             ? request.header("CF-Connecting-IP")
 *             : request.remoteAddress())
 *         .build();
 * }
 *
 * @Bean
 * FilterRegistrationBean<FingerlyAgentSightingsFilter> agentSightingsFilter(AgentSightings sightings) {
 *     return new FilterRegistrationBean<>(new FingerlyAgentSightingsFilter(sightings));
 * }
 * }</pre>
 */
public final class FingerlyAgentSightingsFilter implements Filter {

    private final AgentSightings sightings;

    /**
     * @param sightings the configured sightings; every option lives on it
     */
    public FingerlyAgentSightingsFilter(AgentSightings sightings) {
        this.sightings = Objects.requireNonNull(sightings, "sightings");
    }

    /**
     * What Fingerly last said about this request, from memory only: never
     * waits and never throws. Compare against the {@code VisitorKind}
     * constants; {@link AgentSighting#UNKNOWN} means "serve as usual".
     */
    public AgentSighting lookup(HttpServletRequest request) {
        try {
            return sightings.lookup(describe(request));
        } catch (Exception failOpen) {
            return AgentSighting.UNKNOWN;
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(request, response);
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            return;
        }
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse out = (HttpServletResponse) response;
        try {
            DispatcherType dispatch = http.getDispatcherType();
            if (dispatch != null && dispatch != DispatcherType.REQUEST) {
                // An async, error, forward or include dispatch of a request
                // the REQUEST dispatch already reports, or chose not to.
                return;
            }
            if (http.isAsyncStarted()) {
                http.getAsyncContext().addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent event) {
                        report(http, out);
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) {}

                    @Override
                    public void onError(AsyncEvent event) {}

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                        // A new async cycle drops every listener that does
                        // not register again, and only its end is the end
                        // of the request.
                        event.getAsyncContext().addListener(this);
                    }
                });
                return;
            }
        } catch (RuntimeException notAsync) {
            // Fall through and report what is known now.
        }
        report(http, out);
    }

    /** Holds the request when the response is in scope. Never throws. */
    private void report(HttpServletRequest http, HttpServletResponse out) {
        try {
            String contentType = out.getContentType();
            // Decide on the response first, so an out-of-scope request costs
            // nothing beyond this check.
            if (sightings.reports(path(http), contentType)) {
                sightings.report(describe(http), contentType);
            }
        } catch (Exception ignored) {
            // Reporting never affects the response.
        }
    }

    /**
     * The neutral request, with every header, since the client IP callback
     * may read any of them. The body is never read.
     */
    private static ProxyRequest describe(HttpServletRequest http) {
        ProxyRequest.Builder builder = ProxyRequest.builder()
                .method(http.getMethod())
                .path(path(http))
                .remoteAddress(http.getRemoteAddr());
        Enumeration<String> names = http.getHeaderNames();
        if (names != null) {
            for (String name : Collections.list(names)) {
                Enumeration<String> values = http.getHeaders(name);
                if (values != null) {
                    builder.headers(name, Collections.list(values));
                }
            }
        }
        return builder.build();
    }

    /** The whole request path, as the proxy filter matches it; never the query string. */
    private static String path(HttpServletRequest http) {
        String uri = http.getRequestURI();
        return uri == null ? "" : uri;
    }
}
