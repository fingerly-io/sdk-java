package io.fingerly.server;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One incoming request, as every server standard can describe it.
 *
 * <p>The adapters build this; {@link FingerlyProxy} reads it. Keeping the core
 * off {@code jakarta.servlet}, {@code javax.servlet} and WebFlux is what lets
 * all three refuse and forward identically.
 */
public final class ProxyRequest {

    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final InputStream body;
    private final long contentLength;
    private final String remoteAddress;

    private ProxyRequest(Builder builder) {
        this.method = builder.method == null ? "" : builder.method;
        this.path = builder.path == null ? "" : builder.path;
        this.headers = builder.headers;
        this.body = builder.body == null ? new ByteArrayInputStream(new byte[0]) : builder.body;
        this.contentLength = builder.contentLength;
        this.remoteAddress = builder.remoteAddress;
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** The HTTP method. */
    public String method() {
        return method;
    }

    /**
     * The whole request path, including any context path.
     *
     * <p>Under a servlet that is {@code getContextPath() + getServletPath() +
     * getPathInfo()}, not {@code getPathInfo()} alone.
     */
    public String path() {
        return path;
    }

    /**
     * One header, matched without regard to case.
     *
     * @return the value, or null when the request carried none
     */
    public String header(String name) {
        return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** The declared body length, or -1 when the request did not declare one. */
    public long contentLength() {
        return contentLength;
    }

    /**
     * The address of the peer that opened the connection, as the server saw
     * it, or null when the adapter could not tell.
     *
     * <p>This is the visitor only when nothing sits between the visitor and
     * your server. Behind a CDN or load balancer it is that hop, and the
     * visitor is in a header the hop sets.
     */
    public String remoteAddress() {
        return remoteAddress;
    }

    /** The body stream. {@link FingerlyProxy} reads it under its own limit. */
    InputStream body() {
        return body;
    }

    /** Builds a {@link ProxyRequest}. Adapters use this; applications do not. */
    public static final class Builder {
        private String method;
        private String path;
        private final Map<String, String> headers = new HashMap<>();
        private InputStream body;
        private long contentLength = -1;
        private String remoteAddress;

        private Builder() {}

        /**
         * The connection's peer address. Brackets and an IPv6 zone index are
         * dropped, so what remains is a plain address literal.
         */
        public Builder remoteAddress(String remoteAddress) {
            this.remoteAddress = plainAddress(remoteAddress);
            return this;
        }

        /** The HTTP method. */
        public Builder method(String method) {
            this.method = method;
            return this;
        }

        /** The whole request path, including any context path. */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * Adds one header. Repeats are joined with {@code ", "}, as HTTP
         * defines, so a duplicated header cannot silently drop a value.
         */
        public Builder header(String name, String value) {
            if (name == null || value == null) {
                return this;
            }
            headers.merge(name.toLowerCase(Locale.ROOT), value, (first, next) -> first + ", " + next);
            return this;
        }

        /** Adds every value of one header name. */
        public Builder headers(String name, Iterable<String> values) {
            List<String> collected = new ArrayList<>();
            values.forEach(collected::add);
            collected.forEach(value -> header(name, value));
            return this;
        }

        /** The body stream, read later under the proxy's limit. */
        public Builder body(InputStream body) {
            this.body = body;
            return this;
        }

        /** The body bytes, when the adapter already has them. */
        public Builder body(byte[] body) {
            this.body = new ByteArrayInputStream(body);
            this.contentLength = body.length;
            return this;
        }

        /** The declared body length, or -1 when unknown. */
        public Builder contentLength(long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        /** Builds the immutable request. */
        public ProxyRequest build() {
            return new ProxyRequest(this);
        }
    }

    private static String plainAddress(String value) {
        if (value == null) {
            return null;
        }
        String address = value.trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        int zone = address.indexOf('%');
        if (zone >= 0) {
            address = address.substring(0, zone);
        }
        return address.isEmpty() ? null : address;
    }
}
