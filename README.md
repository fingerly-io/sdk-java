# fingerly-java

Server-side event reads, webhook verification and a same-origin proxy for the
JVM. Java 11 or newer, and Kotlin and Scala through the same artifacts.
Version 0.1.0 is prepared for release; the repository is private and nothing
has been published to Maven Central.

```xml
<dependency>
  <groupId>io.fingerly</groupId>
  <artifactId>fingerly-server</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("io.fingerly:fingerly-server:0.1.0")
```

```java
import io.fingerly.server.Event;
import io.fingerly.server.EventListParams;
import io.fingerly.server.EventPage;
import io.fingerly.server.FingerlyClient;

FingerlyClient fingerly = FingerlyClient.builder()
    .secretKey(System.getenv("FINGERLY_SECRET_KEY"))
    .build();

Event event = fingerly.events().get(requestId);
EventPage page = fingerly.events().list(
    EventListParams.builder().level("high").limit(50).build());
```

Event reads require `fly_sk_{production|staging|development}_…` keys. Requests
go to `https://api.fingerly.io`; `endpoint()` overrides that for a local
API. Public, proxy, management, malformed and unsupported keys are rejected
before network access, by `build()` rather than by the first call. Requests do
not follow redirects. `get` and `list` each perform one request and never
retry. A null, empty, `.` or `..` request id is an `IllegalArgumentException`.

## Artifacts

Java has no single HTTP server standard, so the proxy ships an adapter per
standard. Take the core plus whichever one matches your stack.

| Artifact | Contains | For |
| --- | --- | --- |
| `io.fingerly:fingerly-server` | client, webhooks, proxy core | everything |
| `io.fingerly:fingerly-server-jakarta` | `FingerlyProxyFilter` | Spring Boot 3 and 4, Tomcat 10+, Jetty 11+ |
| `io.fingerly:fingerly-server-javax` | `FingerlyProxyFilter` | Spring Boot 2, Tomcat 9 and older |
| `io.fingerly:fingerly-server-webflux` | `FingerlyProxyWebFilter` | reactive Spring 5.3 to 7 (Spring Boot 2 to 4) |

The servlet APIs and Spring are `provided`: the container supplies them, so
nothing here collides with what your application already has. The only runtime
dependency is `jackson-databind`. Each jar carries its licence and an
`Automatic-Module-Name` matching its package, such as `io.fingerly.server`.

## Blocking and non-blocking reads

`get` and `list` block the calling thread until the API answers. Never call
them from an event loop (WebFlux, Vert.x, Netty handlers). Use `getAsync` and
`listAsync` there instead: they return a `CompletableFuture` that completes
from the HTTP client's own threads, exceptionally with the same exceptions the
blocking methods throw, and cancelling one abandons the call.

```java
Mono<Event> event = Mono.fromFuture(() -> fingerly.events().getAsync(requestId));
```

## Deadlines, limits and connections

`timeout()` bounds the whole exchange, including receiving the body. That
takes a little care on the JVM: the timeout on an `HttpRequest` elapses only
until the response headers arrive, so a server that sent headers and then
trickled would otherwise hold the call open. The SDK enforces its own deadline
instead, and on expiry abandons the call and closes its connection, on every
JDK from 11. `FingerlyProxy` bounds its upstream call the same way.

An answer larger than 4 MiB is refused rather than buffered: the client throws
`FingerlyException`, the proxy answers 502. A gzip or deflate answer is
decoded.

Every client and proxy in a process shares one HTTP/1.1 connection pool, so
building one per request still reuses connections. The pool is replaced every
two minutes so DNS changes are picked up; a request in flight finishes on the
connection it started on. Before JDK 21 a replaced pool cannot be closed and
keeps its idle connections until the JDK's keep-alive timeout (20 minutes by
default), so there it is replaced every 20 minutes instead. There is no cookie
jar. Pass `httpClient()` to use your own client instead; configure it with
`HttpClient.Redirect.NEVER`.

## Errors

Every exception is unchecked and derives from `FingerlyException`, so reading
an event does not force a `throws` clause through call sites that only pass
the result along.

```java
try {
    event = fingerly.events().get(requestId);
} catch (FingerlyApiException e) {
    if (e.isPending()) return "retry";       // not decided yet: read again after e.getRetryAfter() seconds
    if (e.isEventNotFound()) return "refuse"; // never issued, or past retention
    throw e;
}
```

`FingerlyApiException` is thrown for every non-2xx answer, redirects
included. It carries `getStatus()`, and optional `getCode()`, `getDetails()`,
`getRequestId()` and `getRetryAfter()`: the seconds `Retry-After` asked for,
or null. The secret key is stripped from all of them, and a response body is
never echoed, not even in a cause.

A read answers `404` with one of two codes, each with its own check:

- `isPending()`: `event_pending`, the request id was accepted and the event
  is still being recorded. Nothing is decided yet: read again after
  `getRetryAfter()` seconds (the API sends `Retry-After: 2`), or verify
  asynchronously. Do not treat it as fraud.
- `isEventNotFound()`: `event_not_found`, the request id was never issued, or
  the event is past retention.

The client never retries by itself.
A deadline throws `FingerlyTimeoutException`; an unreachable API throws
`FingerlyConnectionException`.

No SDK object prints a credential: `toString()` shows `[redacted]` in place of
every key.

## Unscored events

`Event.getSuspectScore()` returns `Optional<Integer>`, empty when the request
was not scored. Never read an empty score as zero: a visitor about whom
nothing fired scores 0, while a submission that was never enriched has no
score at all. `isScored()` states the distinction at the call site.

```java
if (!event.isScored()) {
    // Decide on something else; do not treat it as clean.
}
```

`getRaw()` keeps the response body as a Jackson tree, so a field the API adds
later is reachable without an SDK upgrade. `getRawJson()` is the same body as
the text it arrived as.

**Spring Boot 4 serializes controller results with Jackson 3**, which cannot
write the Jackson 2 `JsonNode` this SDK returns: returning `event.getRaw()`
from a controller writes the node's accessors instead of the event. Return
`getRawJson()` (or `WebhookEvent.getDataJson()`,
`FingerlyApiException.getDetailsJson()`) with a JSON content type instead.

## Read an event you just identified

An event is usually readable as soon as identification answers. If it is
still being recorded, the read throws a `FingerlyApiException` whose
`isPending()` is true and whose `getRetryAfter()` says when to read again:

```java
Event event;
try {
    event = fingerly.events().get(requestId);
} catch (FingerlyApiException e) {
    if (!e.isPending()) throw e;
    // Not recorded yet, so nothing is decided: read again after
    // e.getRetryAfter() seconds (2 when null), or verify asynchronously.
    return "retry";
}
```

Retry a pending read a bounded number of times, for example from a scheduled
job; never sleep on an event loop.
`isEventNotFound()` means a request id that was never issued, or one past
retention, and is never pending.

The archived document arrives moments after the event itself, so check
`hasDocument()` rather than assuming it is there.

## Verify a backend action

Read the event with a server-only key. Check that its tag matches your action,
that it is recent, that its environment is correct, and that it was scored.
Apply your own policy to the server's verdict. A pending event
(`isPending()`) is not a verdict: retry after `getRetryAfter()` seconds or
verify asynchronously, rather than refusing the action. Do not authorize an action using
scores or visitor identifiers supplied by the browser. Tags correlate events;
they do not replace your application's authentication or replay protection.

## Webhooks

```java
if (!Webhooks.verify(webhookSecret, body, timestamp, signature)) {
    return ResponseEntity.badRequest().build();
}

WebhookEvent event = Webhooks.parse(body);
events.enqueue(event.getId(), body);   // deduplicate on the event ID
```

Verify the exact raw bytes before parsing them. Signatures cover the timestamp
and body. The default replay tolerance is five minutes; persist processed
`getId()` values to deduplicate deliveries. During signing-secret rotation, any
matching signature in the comma-separated header is accepted; at most 32 are
read. Malformed or missing input is a false result, never an exception.

`WebhookEvent.getData()` stays a `JsonNode`, so a new event type needs no SDK
upgrade; `getDataJson()` is the same as text.

## Same-origin proxy

Content blockers refuse browser requests to third-party APIs, and a refused
identification is a visitor never identified. Create a proxy in the dashboard
(**Integration > Proxy keys**) and mount it at the path it issued, such as
`/k7x2qm4a`, and those requests become first-party. The proxy key is what lets
that hop declare the visitor's real address, origin and user agent; a public
key alone never can, so nobody can spoof them.

`FingerlyProxy` holds every decision and speaks to no server API. One of the
adapters mounts it.

```java
FingerlyProxy proxy = FingerlyProxy.builder()
    .proxyKey(System.getenv("FINGERLY_PROXY_KEY"))
    .path("/k7x2qm4a")
    .clientIp(request -> request.header("CF-Connecting-IP") != null
        ? request.header("CF-Connecting-IP")
        : request.remoteAddress())
    .build();
```

> **`clientIp` is the one thing you must get right.** Take the header your CDN
> or load balancer sets when it is there, and the connection's peer address
> otherwise. Trust that header only when traffic can reach your server through
> nothing but that CDN or load balancer. Never read the left-most
> `X-Forwarded-For` value: the visitor writes it.

`clientIp` must return exactly one IPv4 or IPv6 address. Anything else (null,
empty, a list, a port, a host name) is a configuration error: the request is
refused with a 500 saying "The client IP callback returned no valid address",
nothing is sent upstream, and the mistake is logged once.

`path` is optional where the framework already routes only that path to the
proxy, and required by the WebFlux adapter, which sees every request.

### Spring Boot 3 and 4, Tomcat 10+, Jetty 11+

```java
import io.fingerly.server.FingerlyProxy;
import io.fingerly.server.jakarta.FingerlyProxyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FingerlyConfig {

    @Bean
    FingerlyProxy fingerlyProxy() {
        return FingerlyProxy.builder()
            .proxyKey(System.getenv("FINGERLY_PROXY_KEY"))
            .path("/k7x2qm4a")
            .clientIp(request -> request.header("CF-Connecting-IP") != null
                ? request.header("CF-Connecting-IP")
                : request.remoteAddress())
            .build();
    }

    @Bean
    FilterRegistrationBean<FingerlyProxyFilter> fingerlyProxyFilter(FingerlyProxy proxy) {
        FilterRegistrationBean<FingerlyProxyFilter> registration =
            new FilterRegistrationBean<>(new FingerlyProxyFilter(proxy));
        registration.addUrlPatterns("/k7x2qm4a");
        return registration;
    }
}
```

The filter is asynchronous: the container thread is released while Fingerly
answers, so a slow upstream cannot occupy the container's thread pool. Spring
Boot registers filters with async support; in `web.xml`, add
`<async-supported>true</async-supported>` to the filter, or the thread waits
up to the proxy's timeout.

### Spring Boot 2 and older containers

The same, from `io.fingerly.server.javaxservlet`. The package is not
`...javax`, because `javax` is a restricted package name on the module path.

### Reactive Spring

```java
import io.fingerly.server.FingerlyProxy;
import io.fingerly.server.webflux.FingerlyProxyWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FingerlyConfig {

    @Bean
    FingerlyProxyWebFilter fingerlyProxy() {
        return new FingerlyProxyWebFilter(FingerlyProxy.builder()
            .proxyKey(System.getenv("FINGERLY_PROXY_KEY"))
            .path("/k7x2qm4a")
            .clientIp(request -> request.header("CF-Connecting-IP") != null
                ? request.header("CF-Connecting-IP")
                : request.remoteAddress())
            .build());
    }
}
```

Nothing in the filter blocks. The body is read reactively and the upstream
call runs through `FingerlyProxy.handleAsync`, so no thread waits while
Fingerly answers; only starting the call runs on `boundedElastic`, for the
moment it takes. A visitor who disconnects cancels the upstream call.

Then point the browser SDK at the same path:

```ts
const fingerly = await load({ apiKey: 'fly_pk_…', endpoints: '/k7x2qm4a' })
```

### Other servers

Build a `ProxyRequest` (method, raw path, headers, body, `remoteAddress`) and
write the `ProxyResponse` back. `handleAsync` returns a `CompletableFuture`
that always completes normally within the timeout; cancel it when the visitor
disconnects. `handle` is the same, waiting on the calling thread.

### What the proxy does

The browser SDK posts both of its calls to that one path, and the proxy
forwards every `POST` it answers to Fingerly's one ingest URL,
`https://api.fingerly.io/api/v1/ingest` (`apiVersion` changes only the version
in it). Any other method gets `405`. Event reads need a secret key and stay
unreachable from a browser by construction.

With `path` set, the proxy answers exactly that path, compared with the whole
raw request path including any context path, case-sensitively. A trailing or
doubled slash, a dot segment, percent encoding or a path parameter makes a
different path: it gets `404`, or passes through to the application under a
filter, as does anything under the path. Without `path`, it answers whatever
the framework routed to it. `path` must be a plain absolute path such as
`/k7x2qm4a`.

The browser must present a public key from the same environment as the proxy
key, or the request gets `401`. Bodies over 1 MiB get `413`,
enforced while reading rather than after buffering. Only the content type, the
visitor metadata Fingerly needs, and an `Idempotency-Key` are forwarded;
customer cookies and authorization headers never are, and a visitor header that
could not be forwarded is dropped. The proxy reports itself upstream as
`java/0.1.0`, which the dashboard shows per proxy.

Only `Content-Type`, `X-Request-Id`, `RateLimit-Limit`, `RateLimit-Remaining`
and `Retry-After` are relayed back, plus `Cache-Control: no-store`, so nothing
on the response names Fingerly. The answer is read whole before anything is
relayed, so a truncated one is never passed on as a success.

When the upstream fails, the browser gets `504` ("Gateway timeout") if it did
not answer within `timeout()` (5 seconds by default), and `502` ("Bad
gateway") for anything else: refused, reset, truncated, larger than 4 MiB, or
compressed in a way that cannot be decoded. One line is logged per failure,
without a stack trace or a key, to the `System.Logger` named
`io.fingerly.server.FingerlyProxy` at `WARNING` (which reaches SLF4J, Logback
or Log4j through their usual bridges), or to your own `log(...)` callback.

Every adapter asks `proxy.handles(path)` before reading anything. A filter may
be mapped more broadly than the one path the proxy owns, and calling
`handle()` to find out would have consumed a request body the application
still needs.

## Development and release preparation

```sh
./gradlew build                                        # compile, test (Spring 5.3 and 7), javadoc, jars
FINGERLY_CONTRACT_DIR=../../contract java tools/SyncContract.java
```

The build runs on a Java 17 toolchain and emits Java 11 bytecode with
`--release`, so a method that exists only on a newer JDK cannot slip in
unnoticed. Warnings are errors: a warning the SDK ships is one its users
cannot fix.

The vendored synthetic contract snapshot makes tests independent of sibling
repositories. Its hashes are checked by `ContractTest`, so there is no separate
check step. Never put production secrets in fixtures.

## Source structure

`FingerlyClient` holds the options; `EventsService` owns the reads and the list
filters, and `Event`, `EventPage` and `EventListParams` are what they speak in.
`Webhooks` verifies signatures. `FingerlyProxy` holds every proxy decision,
with `ProxyRequest` and `ProxyResponse` as the neutral shapes the adapters
translate to. `SharedHttp` is the process-wide connection pool, `BoundedBody`
reads every answer within its limit, and `Deadlines` is the one timer thread
behind every deadline. `Keys` validates every caller-supplied option before
any network access, and `Json` keeps the Jackson configuration in one place so
a caller's own `ObjectMapper` cannot change how responses are read.
