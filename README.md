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

```java
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
retry.

## Artifacts

Java has no single HTTP server standard, so the proxy ships an adapter per
standard. Take the core plus whichever one matches your stack.

| Artifact | Contains | For |
| --- | --- | --- |
| `io.fingerly:fingerly-server` | client, webhooks, proxy core | everything |
| `io.fingerly:fingerly-server-jakarta` | `FingerlyProxyFilter` | Spring Boot 3, Tomcat 10+, Jetty 11+ |
| `io.fingerly:fingerly-server-javax` | `FingerlyProxyFilter` | Spring Boot 2, Tomcat 9 and older |
| `io.fingerly:fingerly-server-webflux` | `FingerlyProxyWebFilter` | reactive Spring, 5.3 and 6.x |

The servlet APIs and Spring are `provided`: the container supplies them, so
nothing here collides with what your application already has. The only runtime
dependency is `jackson-databind`.

## Deadlines and cancellation

`timeout()` bounds the whole exchange, including receiving the body. That
takes a little care on the JVM: the timeout on an `HttpRequest` elapses only
until the response headers arrive, so a server that sent headers and then
stalled would otherwise hold the call open. The client waits on the response
with its own deadline instead, and cancels on expiry so the connection is
released rather than left to finish unread.

## Errors

Every exception is unchecked and derives from `FingerlyException`, so reading
an event does not force a `throws` clause through call sites that only pass
the result along.

```java
try {
    event = fingerly.events().get(requestId);
} catch (FingerlyApiException e) {
    if (e.getStatus() == 404) return "refuse";
    throw e;
}
```

`FingerlyApiException` carries `getStatus()`, and optional `getCode()`,
`getDetails()` and `getRequestId()`. The secret key is stripped from all three,
and the response body is never echoed. `isEventNotFound()` names the one
retryable read failure, so it needs no status-and-code check of your own.
A deadline throws `FingerlyTimeoutException`; an unreachable API throws
`FingerlyConnectionException`.

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

`getRaw()` keeps the response body exactly as it arrived, so a field the API
adds later is reachable without an SDK upgrade.

## Read an event you just identified

An event is readable as soon as identification answers, so this needs nothing
special:

```java
Event event = fingerly.events().get(requestId);
```

Earlier versions had a `waitFor` that polled through the gap between
identification and the event becoming queryable. There is no gap, so
`isEventNotFound()` now means a request id that was never issued.

The archived document arrives moments after the event itself, so check
`hasDocument()` rather than assuming it is there.

## Verify a backend action

Read the event with a server-only key. Check that its tag matches your action,
that it is recent, that its environment is correct, and that it was scored.
Apply your own policy to the server's verdict. Do not authorize an action using
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
matching signature in the comma-separated header is accepted. Malformed or
missing input is a false result, never an exception.

`WebhookEvent.getData()` stays a `JsonNode`, so a new event type needs no SDK
upgrade.

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
    .clientIp(request -> request.header("CF-Connecting-IP"))
    .build();
```

`path` is optional where the framework already routes only that path to the
proxy, and required by the WebFlux adapter, which sees every request.

### Spring Boot 3, Tomcat 10+, Jetty 11+

```java
@Bean
FilterRegistrationBean<FingerlyProxyFilter> fingerlyProxy(FingerlyProxy proxy) {
    var registration = new FilterRegistrationBean<>(new FingerlyProxyFilter(proxy));
    registration.addUrlPatterns("/k7x2qm4a");
    return registration;
}
```

### Spring Boot 2 and older containers

The same, from `io.fingerly.server.javaxservlet`. The package is not
`...javax`, because `javax` is a restricted package name on the module path.

### Reactive Spring

```java
@Bean
FingerlyProxyWebFilter fingerlyProxy(FingerlyProxy proxy) {
    return new FingerlyProxyWebFilter(proxy);
}
```

**The forward is blocking.** `FingerlyProxy` calls Fingerly with a synchronous
HTTP client, which must never run on an event loop, so the WebFlux adapter
moves that call to `Schedulers.boundedElastic()`. Pass your own scheduler to
the two-argument constructor if you would rather it shared a pool you control.
The request body is read reactively either way; only the upstream call is
offloaded.

Then point the browser SDK at the same path:

```ts
const fingerly = await load({ apiKey: 'fly_pk_…', endpoints: '/k7x2qm4a' })
```

### What the proxy does

The browser SDK posts both of its calls to that one path, and the proxy
forwards every `POST` it answers to Fingerly's one ingest URL,
`https://api.fingerly.io/api/v1/ingest` (`apiVersion` changes only the version
in it). Any other method gets `405`. Event reads need a secret key and stay
unreachable from a browser by construction.

With `path` set, the proxy answers that path exactly, matched against the whole
request path including any context path, with trailing slashes ignored.
Anything else, including a path under it, gets `404`, or passes through to the
application under a filter. Without `path`, it answers whatever the framework
routed to it. `path` must start with `/` and cannot contain `?` or `#`.

The browser must present a public key from the same environment as the proxy
key, or the request gets `401`. Bodies over 1 MiB get `413`,
enforced while reading rather than after buffering. Only the content type, the
visitor metadata Fingerly needs, and an `Idempotency-Key` are forwarded;
customer cookies and authorization headers never are. The proxy reports itself
upstream as `java/0.1.0`, which the dashboard shows per proxy.

Only `Content-Type`, `X-Request-Id`, `RateLimit-Limit`, `RateLimit-Remaining`
and `Retry-After` are relayed back, plus `Cache-Control: no-store`, so nothing
on the response names Fingerly.

Every adapter asks `proxy.handles(path)` before reading anything. A filter may
be mapped more broadly than the one path the proxy owns, and calling
`handle()` to find out would have consumed a request body the application
still needs.

> **`clientIp` is the one thing you must get right.** Resolve it from a header
> your own infrastructure sets, such as your load balancer's or CDN's. Never
> read the left-most `X-Forwarded-For` value: the visitor writes it.

## Development and release preparation

```sh
./gradlew build                                        # compile, test, javadoc, jars
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

`FingerlyClient` holds the connection and the options; `EventsService` owns the
reads and the list filters, and `Event`, `EventPage` and `EventListParams` are
what they speak in. `Webhooks` verifies signatures. `FingerlyProxy` holds every
proxy decision, with `ProxyRequest` and `ProxyResponse` as the neutral shapes
the adapters translate to. `Keys` validates every caller-supplied option before
any network access, and `Json` keeps the Jackson configuration in one place so
a caller's own `ObjectMapper` cannot change how responses are read.
