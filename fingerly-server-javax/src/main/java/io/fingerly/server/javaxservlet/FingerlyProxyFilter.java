package io.fingerly.server.javaxservlet;

import io.fingerly.server.FingerlyProxy;
import io.fingerly.server.ProxyRequest;
import io.fingerly.server.ProxyResponse;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Serves the browser SDK's same-origin requests under the older Servlet API.
 *
 * <p>For Spring Boot 2, Tomcat 9 and older, and anything else still on
 * {@code javax.servlet}. Spring Boot 3 and newer containers want
 * {@code fingerly-server-jakarta} instead.
 *
 * <p>The package is {@code io.fingerly.server.javaxservlet}, not
 * {@code ...javax}, because {@code javax} is a restricted package name on the
 * module path.
 *
 * <p>Every decision belongs to {@link FingerlyProxy}. This class only reads a
 * request and writes a response, so both servlet generations behave alike.
 *
 * <p>Where the request supports it, the filter goes asynchronous: the body is
 * read on the container thread, which is then released while Fingerly
 * answers, so a slow upstream cannot occupy the container's thread pool.
 * Spring Boot registers filters with async support; in {@code web.xml} add
 * {@code <async-supported>true</async-supported>}. Without it the thread
 * waits, up to the proxy's timeout.
 *
 * <p>Map the filter to the path the dashboard issued, such as
 * {@code /k7x2qm4a}, and it answers every request mapped to it. Mapped more
 * broadly, give the proxy the same {@link FingerlyProxy.Builder#path path} and
 * every other request passes through to the application untouched.
 *
 * <pre>{@code
 * @Bean
 * FilterRegistrationBean<FingerlyProxyFilter> fingerlyProxy() {
 *     FingerlyProxy proxy = FingerlyProxy.builder()
 *         .proxyKey(System.getenv("FINGERLY_PROXY_KEY"))
 *         .clientIp(request -> request.header("CF-Connecting-IP"))
 *         .build();
 *
 *     var registration = new FilterRegistrationBean<>(new FingerlyProxyFilter(proxy));
 *     registration.addUrlPatterns("/k7x2qm4a");
 *     return registration;
 * }
 * }</pre>
 */
public final class FingerlyProxyFilter implements Filter {

    private final FingerlyProxy proxy;

    /**
     * @param proxy the configured proxy; every option lives on it
     */
    public FingerlyProxyFilter(FingerlyProxy proxy) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse out = (HttpServletResponse) response;

        // Ask before reading: the filter may be mapped more broadly than the
        // one path the proxy owns, and handle() would consume a body the
        // application still needs.
        if (!proxy.handles(fullPath(http))) {
            chain.doFilter(request, response);
            return;
        }
        if (!http.isAsyncSupported()) {
            // A container or registration without async support: the thread
            // waits, as it would for any other blocking call.
            write(out, proxy.handle(toProxyRequest(http)));
            return;
        }

        // Release the container thread while the upstream answers. The body
        // is read here first, within the proxy's limit.
        AsyncContext async = http.startAsync();
        async.setTimeout(0); // the proxy's own deadline bounds the exchange
        CompletableFuture<ProxyResponse> pending = proxy.handleAsync(toProxyRequest(http));
        async.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {}

            @Override
            public void onTimeout(AsyncEvent event) {
                pending.cancel(true);
            }

            @Override
            public void onError(AsyncEvent event) {
                // The visitor disconnected: abandon the upstream call.
                pending.cancel(true);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {}
        });
        pending.thenAccept(result -> async.start(() -> {
            try {
                write((HttpServletResponse) async.getResponse(), result);
            } catch (IOException | RuntimeException visitorGone) {
                // Nobody is left to answer; end quietly.
            } finally {
                try {
                    async.complete();
                } catch (IllegalStateException alreadyDone) {
                    // The container finished the request itself.
                }
            }
        }));
    }

    /**
     * Builds the neutral request.
     *
     * <p>The context path and servlet path are included, because the proxy
     * matches the whole request path and a container may report it in pieces.
     */
    private static ProxyRequest toProxyRequest(HttpServletRequest http) throws IOException {
        ProxyRequest.Builder builder = ProxyRequest.builder()
                .method(http.getMethod())
                .path(fullPath(http))
                .body(http.getInputStream())
                .contentLength(http.getContentLengthLong())
                .remoteAddress(http.getRemoteAddr());

        for (String name : Collections.list(http.getHeaderNames())) {
            builder.headers(name, Collections.list(http.getHeaders(name)));
        }
        return builder.build();
    }

    private static String fullPath(HttpServletRequest http) {
        String uri = http.getRequestURI();
        return uri == null ? "" : uri;
    }

    private static void write(HttpServletResponse out, ProxyResponse result) throws IOException {
        out.setStatus(result.status());
        for (Map.Entry<String, String> header : result.headers()) {
            out.addHeader(header.getKey(), header.getValue());
        }
        out.setContentLength(result.contentLength());
        out.getOutputStream().write(result.body());
    }
}
