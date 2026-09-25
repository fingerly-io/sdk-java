package io.fingerly.server.javaxservlet;

import io.fingerly.server.FingerlyProxy;
import io.fingerly.server.ProxyRequest;
import io.fingerly.server.ProxyResponse;
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
        write(out, proxy.handle(toProxyRequest(http)));
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
                .contentLength(http.getContentLengthLong());

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
