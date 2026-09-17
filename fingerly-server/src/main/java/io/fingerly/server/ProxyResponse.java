package io.fingerly.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One response for an adapter to write out.
 *
 * <p>Headers are a list rather than a map, because a response may legitimately
 * repeat a name and collapsing those would lose values.
 */
public final class ProxyResponse {

    private final int status;
    private final List<Map.Entry<String, String>> headers;
    private final byte[] body;

    ProxyResponse(int status, List<Map.Entry<String, String>> headers, byte[] body) {
        this.status = status;
        this.headers = List.copyOf(headers);
        this.body = body;
    }

    /** The HTTP status code. */
    public int status() {
        return status;
    }

    /** The response headers, in order, possibly repeating a name. */
    public List<Map.Entry<String, String>> headers() {
        return headers;
    }

    /** The body bytes. Empty for a status that must not carry one. */
    public byte[] body() {
        return body.clone();
    }

    /** The body's length, without copying it. */
    public int contentLength() {
        return body.length;
    }

    /** Writes the body into a sink, avoiding the copy {@link #body()} makes. */
    void writeBodyTo(java.io.OutputStream sink) throws java.io.IOException {
        sink.write(body);
    }

    /** Builds a plain-text response that never reached Fingerly. */
    static ProxyResponse refusal(int status, String message) {
        byte[] body = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(Map.entry("Content-Type", "text/plain; charset=utf-8"));
        headers.add(Map.entry("Cache-Control", "no-store"));
        return new ProxyResponse(status, Collections.unmodifiableList(headers), body);
    }
}
