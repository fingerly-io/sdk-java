<p align="center">
  <a href="https://fingerly.io">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fingerly-io/.github/main/assets/logo-dark.svg">
      <img src="https://raw.githubusercontent.com/fingerly-io/.github/main/assets/logo-light.svg" alt="Fingerly" height="44">
    </picture>
  </a>
</p>

<p align="center"><b>Fingerly for Java</b><br>Read events, verify webhooks and serve a first-party proxy from your JVM backend, with Spring Boot and WebFlux adapters.</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.fingerly/fingerly-server"><img src="https://img.shields.io/maven-central/v/io.fingerly/fingerly-server?style=flat-square" alt="Maven Central version"></a>
  <a href="https://github.com/fingerly-io/sdk-java/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/fingerly-io/sdk-java/ci.yml?branch=main&style=flat-square&label=CI" alt="CI status"></a>
  <img src="https://img.shields.io/badge/java-11%2B-blue?style=flat-square" alt="Java 11 or newer">
  <a href="https://github.com/fingerly-io/sdk-java/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue?style=flat-square" alt="Apache-2.0 licence"></a>
  <a href="https://docs.fingerly.io/docs/sdks/java"><img src="https://img.shields.io/badge/docs-docs.fingerly.io-blue?style=flat-square" alt="Documentation"></a>
</p>

<p align="center"><img src="https://raw.githubusercontent.com/fingerly-io/.github/main/assets/banner.webp" alt="Fingerly" width="100%"></p>

## What is this

`fingerly-java` is the server-side package for the JVM for [Fingerly](https://fingerly.io), which identifies the device behind every web and app session and scores how suspicious it is. Use it to protect signups, logins and payments: read the event the browser SDK produced with a server-only secret key, verify webhook signatures, and relay the browser SDK's traffic through your own domain.

Java 11 or newer, and Kotlin and Scala through the same artifacts. Releases are published to Maven Central from [github.com/fingerly-io/sdk-java](https://github.com/fingerly-io/sdk-java).

## Getting started

**1. Prerequisites.** A Fingerly account and a secret key (see [API keys and environments](https://docs.fingerly.io/docs/api-keys)). A webhook signing secret to verify webhooks, and a proxy key (see [Proxy integrations](https://docs.fingerly.io/docs/proxy-integrations)) for the proxy.

**2. Install.**

```xml
<dependency>
  <groupId>io.fingerly</groupId>
  <artifactId>fingerly-server</artifactId>
  <version>0.4.1</version>
</dependency>
```

```kotlin
implementation("io.fingerly:fingerly-server:0.4.1")
```

For the proxy, add the adapter for your stack as well (see [Artifacts](#artifacts)).

**3. Read an event.**

```java
import io.fingerly.server.Event;
import io.fingerly.server.EventListParams;
import io.fingerly.server.EventPage;
import io.fingerly.server.FingerlyClient;
import io.fingerly.server.SuspectLevel;

FingerlyClient fingerly = FingerlyClient.builder()
    .secretKey(System.getenv("FINGERLY_SECRET_KEY"))
    .build();

Event event = fingerly.events().get(requestId);
EventPage page = fingerly.events().list(
    EventListParams.builder().level(SuspectLevel.HIGH).limit(50).build());
```

**4. Verify a webhook.**

```java
if (!Webhooks.verify(webhookSecret, body, timestamp, signature)) {
    return ResponseEntity.badRequest().build();
}

WebhookEvent event = Webhooks.parse(body);
events.enqueue(event.getId(), body);   // deduplicate on the event ID
```

Full documentation: [docs.fingerly.io/docs/sdks/java](https://docs.fingerly.io/docs/sdks/java).

## Choose what you need

| I want to | Go to |
| --- | --- |
| Read an event, or verify a backend action | [Read events](#read-events), [Verify a backend action](#verify-a-backend-action) |
| Read without blocking a thread | [Blocking and non-blocking reads](#blocking-and-non-blocking-reads) |
| Handle errors and unscored events | [Errors](#errors), [Unscored events](#unscored-events) |
| Allow one free trial per iOS device | [DeviceCheck bits on iOS](#devicecheck-bits-on-ios) |
| Check a webhook signature | [Verify webhooks](#verify-webhooks) |
| Keep identification working behind content blockers | [First-party proxy](#first-party-proxy) |
| Pick the adapter for Spring Boot, Tomcat, Jetty or WebFlux | [Artifacts](#artifacts), [Adapters](#adapters) |

## Contents

- [Artifacts](#artifacts)
- [Read events](#read-events)
- [Blocking and non-blocking reads](#blocking-and-non-blocking-reads)
- [Deadlines, limits and connections](#deadlines-limits-and-connections)
- [Errors](#errors)
- [Unscored events](#unscored-events)
- [Read an event you just identified](#read-an-event-you-just-identified)
- [Named values](#named-values)
- [Verify a backend action](#verify-a-backend-action)
- [DeviceCheck bits on iOS](#devicecheck-bits-on-ios)
- [Verify webhooks](#verify-webhooks)
- [First-party proxy](#first-party-proxy)
- [Development and release preparation](#development-and-release-preparation)
- [Requirements](#requirements)
- [Documentation](#documentation)
- [Help](#help)
- [Licence](#licence)

## Artifacts

Java has no single HTTP server standard, so the proxy ships an adapter per standard. Take the core plus whichever one matches your stack.

| Artifact | Contains | For |
| --- | --- | --- |
| `io.fingerly:fingerly-server` | client, webhooks, proxy core | everything |
| `io.fingerly:fingerly-server-jakarta` | `FingerlyProxyFilter` | Spring Boot 3 and 4, Tomcat 10+, Jetty 11+ |
| `io.fingerly:fingerly-server-javax` | `FingerlyProxyFilter` | Spring Boot 2, Tomcat 9 and older |
| `io.fingerly:fingerly-server-webflux` | `FingerlyProxyWebFilter` | reactive Spring 5.3 to 7 (Spring Boot 2 to 4) |

The servlet APIs and Spring are `provided`: the container supplies them, so nothing here collides with what your application already has. The only runtime dependency is `jackson-databind`. Each jar carries its licence and an `Automatic-Module-Name` matching its package, such as `io.fingerly.server`.

## Read events

Event reads require `fly_sk_{production|staging|development}_…` keys. Requests go to `https://api.fingerly.io`; `endpoint()` overrides that. It takes `https://host` with an optional port and nothing else: a path, query, fragment, credentials, whitespace or control byte is refused by `build()`. A plain `http` endpoint is refused too, except for a local API with `allowInsecureLoopback(true)` and a host of `127.0.0.1`, `localhost` or `[::1]`. Public, proxy, management, malformed and unsupported keys are rejected before network access, by `build()` rather than by the first call. Requests do not follow redirects. `get` and `list` each perform one request and never retry. A null, empty, `.` or `..` request id is an `IllegalArgumentException`.

`getProjectId()` is the UUID of the Fingerly project the event belongs to, on `get` and on every `list` row; it is null when an older server does not send it.

`getAi()` is present only when `getVisitorKind()` is `VisitorKind.AI_BOT`: an `AiAgent` with the agent's name, provider, purpose, runtime, `isDeclared()` and `getVerification()`. The identity comes from the user agent, which anyone can copy, and is not verified yet (`AiVerificationMethod.NONE`, `AiVerificationResult.NOT_ATTEMPTED`), so treat it as a label, not proof. Only clients that run the Fingerly SDK produce events, so crawlers that do not run JavaScript never appear. `EventListParams.Builder` filters on `kind()`, `aiProvider()` (exact and case-sensitive, such as `OpenAI`) and `aiPurpose()`.

```java
EventPage fetches = fingerly.events().list(EventListParams.builder()
    .kind(VisitorKind.AI_BOT)
    .aiPurpose(AiPurpose.USER_FETCH)
    .build());
```

## Blocking and non-blocking reads

`get` and `list` block the calling thread until the API answers. Never call them from an event loop (WebFlux, Vert.x, Netty handlers). Use `getAsync` and `listAsync` there instead: they return a `CompletableFuture` that completes from the HTTP client's own threads, exceptionally with the same exceptions the blocking methods throw, and cancelling one abandons the call.

```java
Mono<Event> event = Mono.fromFuture(() -> fingerly.events().getAsync(requestId));
```

## Deadlines, limits and connections

`timeout()` bounds the whole exchange, including receiving the body. That takes a little care on the JVM: the timeout on an `HttpRequest` elapses only until the response headers arrive, so a server that sent headers and then trickled would otherwise hold the call open. The SDK enforces its own deadline instead, and on expiry abandons the call and closes its connection, on every JDK from 11. `FingerlyProxy` bounds its upstream call the same way.

An answer larger than 4 MiB is refused rather than buffered: the client throws `FingerlyException`, the proxy answers 502. A gzip or deflate answer is decoded.

Every client and proxy in a process shares one HTTP/1.1 connection pool, so building one per request still reuses connections. The pool is replaced every two minutes so DNS changes are picked up; a request in flight finishes on the connection it started on. Before JDK 21 a replaced pool cannot be closed and keeps its idle connections until the JDK's keep-alive timeout (20 minutes by default), so there it is replaced every 20 minutes instead. There is no cookie jar. Pass `httpClient()` to use your own client instead; it must be built with `HttpClient.Redirect.NEVER` (the JDK default). `FingerlyProxy` refuses any other redirect policy when it is built, since every upstream call carries the proxy key.

## Errors

Every exception is unchecked and derives from `FingerlyException`, so reading an event does not force a `throws` clause through call sites that only pass the result along.

```java
try {
    event = fingerly.events().get(requestId);
} catch (FingerlyApiException e) {
    if (e.isPending()) return "retry";       // not decided yet: read again after e.getRetryAfter() seconds
    if (e.isEventNotFound()) return "refuse"; // never issued, or past retention
    throw e;
}
```

| Exception | Thrown when |
| --- | --- |
| `FingerlyApiException` | Every non-2xx answer, redirects included. Carries `getStatus()`, and optional `getCode()`, `getDetails()`, `getRequestId()` and `getRetryAfter()`: the seconds `Retry-After` asked for, or null. |
| `FingerlyTimeoutException` | A deadline passed. |
| `FingerlyConnectionException` | The API is unreachable. |

The secret key is stripped from every field of a `FingerlyApiException`, and a response body is never echoed, not even in a cause.

A read answers `404` with one of two codes, each with its own check:

| Check | Code | Meaning |
| --- | --- | --- |
| `isPending()` | `event_pending` | The request id was accepted and the event is still being recorded. Nothing is decided yet: read again after `getRetryAfter()` seconds (the API sends `Retry-After: 2`), or verify asynchronously. Do not treat it as fraud. |
| `isEventNotFound()` | `event_not_found` | The request id was never issued, or the event is past retention. |

The client never retries by itself.

No SDK object prints a credential: `toString()` shows `[redacted]` in place of every key.

## Unscored events

`Event.getSuspectScore()` returns `Optional<Integer>`, empty when the request was not scored. Never read an empty score as zero: a visitor about whom nothing fired scores 0, while a submission that was never enriched has no score at all. `isScored()` states the distinction at the call site.

```java
if (!event.isScored()) {
    // Decide on something else; do not treat it as clean.
}
```

`getRaw()` keeps the response body as a Jackson tree, so a field the API adds later is reachable without an SDK upgrade. `getRawJson()` is the same body as the text it arrived as.

**Spring Boot 4 serializes controller results with Jackson 3**, which cannot write the Jackson 2 `JsonNode` this SDK returns: returning `event.getRaw()` from a controller writes the node's accessors instead of the event. Return `getRawJson()` (or `WebhookEvent.getDataJson()`, `FingerlyApiException.getDetailsJson()`) with a JSON content type instead.

## Read an event you just identified

An event is usually readable as soon as identification answers. If it is still being recorded, the read throws a `FingerlyApiException` whose `isPending()` is true and whose `getRetryAfter()` says when to read again:

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

Retry a pending read a bounded number of times, for example from a scheduled job; never sleep on an event loop. `isEventNotFound()` means a request id that was never issued, or one past retention, and is never pending.

The archived document arrives moments after the event itself, so check `hasDocument()` rather than assuming it is there, and read `getDocument()` for the allow-listed archived `JsonNode` (`risk`, `attestation`, `ip_network` and `velocity`, with no raw signals, user agent or address). When a submission is unidentifiable (`identifiable == false`, such as insufficient entropy, a genuine embedded WebView, or an unmatched high-risk Web payload), `getVisitorId()` is `""`; there are no `deferred_token`, `deferred_signals` or `supplements` fields in `v: 3`.

## Named values

The string fields with a fixed set of values have a class of constants each, holding the exact strings the API sends. The accessors stay plain strings, so a value the server adds after this SDK was released still reads normally: compare with `equals` or `switch`, and keep a default branch.

| Constants | Values of |
| --- | --- |
| `Signal` | `EventTrigger.getSignal()` (signal groups, such as `Signal.VPN`) and the archived document's `risk.triggers[].signal` (detectors, such as `Signal.VPN_PUBLIC`) |
| `SuspectLevel` | `Event.getSuspectLevel()` and `EventListParams.Builder.level()` |
| `Confidence` | `EventTrigger.getConfidence()` |
| `EventState`, `Environment`, `Platform` | `Event.getState()`, `getEnvironment()`, `getPlatform()` |
| `AnonymityNetwork`, `DeviceKind`, `VisitorKind` | `Event.getAnonymityNetwork()`, `getDeviceKind()`, `getVisitorKind()`, and `EventListParams.Builder.kind()` |
| `AiPurpose`, `AiRuntime` | `AiAgent.getPurpose()` (and `EventListParams.Builder.aiPurpose()`), `getRuntime()` |
| `AiVerificationMethod`, `AiVerificationResult` | `AiVerification.getMethod()`, `getResult()` |
| `AttestationState`, `AttestationStatus`, `AttestSkippedReason` | the archived document's `attestation.state`, `attestation.status` and `attestation.attest_skipped` |
| `VerifyReason` | `VerifyResult.getReason()` |
| `WebhookEventType`, `WebhookEnvironment` | `WebhookEvent.getType()`, `getEnvironment()` |

```java
boolean suppressed = event.getTriggers().stream()
        .anyMatch(t -> Signal.FINGERPRINT_SUPPRESSED.equals(t.getSignal()));
```

`EventTrigger.V3_SIGNALS` groups the `v: 3` anti-spoofing and integrity signals: `Signal.ATTESTATION_FAILED`, `ATTESTATION_DOWNGRADE`, `DEVICE_INCONSISTENT`, `REPORT_INCOMPLETE`, `PROBE_ERRORS`, `FETCH_METADATA_MISSING`, `WEB_TICKET_MISSING`, `WEB_TICKET_INVALID`, `TAMPERING`, `CUSTOM_ROOT_CA` and `SECONDARY_USER_PROFILE`. The `EventTrigger.SIGNAL_` constants are the same strings.

## Verify a backend action

Send the request id from the browser or app to your backend and verify it there with a server-only key. `fingerly.events().verify(requestId, options)` reads the event and checks, in this order, stopping at the first that fails:

1. `not_found`: no event has this request id (never issued, or past retention).
2. `too_old`: the event is older than `maxAgeSeconds` (300 by default). A missing or unreadable `occurred_at` counts as too old.
3. `tag_mismatch`: a `tag` was set and the event's tag is not exactly it. An untagged event never matches.
4. `unidentifiable`: the event's `getVisitorId()` is `""`.
5. `unscored`: the event was not scored.
6. `already_consumed`: every check passed, but another call consumed the event first.

It consumes the event last, only when every other check passes, so one event backs one decision: a second verify of the same request id answers `already_consumed`. `VerifyOptions.builder().consume(false)` skips the consume. `getReason()` is one of the `VerifyReason` constants (`VerifyReason.OK`, `""`, when `isOk()`), and `getEvent()` is the event read (null only for `not_found`).

`verify` never looks at the suspect score, level or triggers: that policy stays with you, applied after `isOk()`.

```java
import io.fingerly.server.Signal;
import io.fingerly.server.SuspectLevel;
import io.fingerly.server.VerifyOptions;
import io.fingerly.server.VerifyResult;

VerifyResult result;
try {
    result = fingerly.events().verify(requestId,
            VerifyOptions.builder().tag("checkout").maxAgeSeconds(120).build());
} catch (FingerlyApiException e) {
    if (e.isPending()) return "retry"; // not decided yet: read again after e.getRetryAfter() seconds
    throw e;
}
if (!result.isOk()) {
    return "refuse"; // result.getReason() says which check failed
}
Event event = result.getEvent();
if (SuspectLevel.HIGH.equals(event.getSuspectLevel())
        || event.getTriggers().stream()
                .anyMatch(t -> Signal.ATTESTATION_FAILED.equals(t.getSignal()))) {
    return "refuse";
}
```

`verify(requestId)` uses the defaults, and `verifyAsync` returns a `CompletableFuture` for non-blocking code. Invalid options (a `maxAgeSeconds` that is not positive, or an empty `tag`) throw `IllegalArgumentException` at `build()`, before any request. Any other error, `event_pending` included, is thrown as from `get`. A pending event (`isPending()`) is not a verdict: retry after `getRetryAfter()` seconds or verify asynchronously, rather than refusing the action.

To consume an event yourself, call `fingerly.events().consume(requestId)` (or `consumeAsync`). The first call returns a `ConsumeResult` with `getConsumedAt()` and `isFirst()`; every later one throws `EventAlreadyConsumedException`, a `FingerlyApiException` with status `409`, code `event_already_consumed` and `getConsumedAt()`. Consumes are never retried.

Do not authorize an action using scores or visitor identifiers supplied by the browser. Tags correlate events; they do not replace your application's authentication. Never put a personal identifier (user ID, email, install ID or advertising ID) in a tag: the server keeps tags in long-lived rollups.

## DeviceCheck bits on iOS

For an iOS identification whose App Attest evidence carried an Apple DeviceCheck token, Fingerly holds the token for one hour, and your backend can spend it once to read or to write the device's two bits. The bits survive a reinstall, and they mean what you decide.

Apple treats a DeviceCheck token as single-use, so a read and a write each need their own identification. A once-per-device free trial takes two:

1. Query on the identification that asks for the trial. If the bits say the device already had one, refuse it.
2. Otherwise grant the trial and record the grant in your own data, then write the bit on the device's next identification. Until the write lands, your own record is what stops a second trial.

```java
import io.fingerly.server.DeviceCheckBits;
import io.fingerly.server.DeviceCheckWrite;
import io.fingerly.server.FingerlyApiException;

// On the identification that asks for the trial.
try {
    DeviceCheckBits bits = fingerly.deviceCheck().query(requestId);
    if (bits.isFound() && bits.getBit0()) {
        return "offer-paid-plan";
    }
} catch (FingerlyApiException e) {
    if (!e.isDeviceCheckNotHeld() && !e.isDeviceCheckSpent()) throw e;
    // No token for this event: decide without the bits.
}
trials.grant(accountId, event.getVisitorId());

// On the next identification from the same install.
fingerly.deviceCheck().write(nextRequestId, DeviceCheckWrite.builder().bit0(true).build());
```

`queryAsync` and `writeAsync` return a `CompletableFuture` for non-blocking code. A write names `bit0`, `bit1` or both; `DeviceCheckWrite.builder().build()` with neither throws `IllegalArgumentException`.

The SDK retries a call, with the identical request, only after a `503` or a transport failure, up to twice. The API keeps the token after such an answer only for a retry of the same request: the other operation, or a write with other bits, answers `409 device_check_spent`. Errors arrive as `FingerlyApiException`: `isDeviceCheckNotHeld()` (`404`), `isDeviceCheckSpent()`, `isDeviceCheckBusy()`, `isCredentialUnavailable()` (`409`), `isBitsRequired()`, `isAppleRejected()` (`422`, with Apple's status in `getAppleStatus()`) and `isDeviceCheckUnavailable()` (`503`). Never rely on the bits as the only fraud check, and give customers a way to have them reset.

## Verify webhooks

The example under [Getting started](#getting-started) verifies and parses a delivery.

Verify the exact raw bytes before parsing them. Signatures cover the timestamp and body. The default replay tolerance is five minutes; persist processed `getId()` values to deduplicate deliveries. During signing-secret rotation, any matching signature in the comma-separated header is accepted; at most 32 are read. Malformed or missing input is a false result, never an exception.

`WebhookEvent.getData()` stays a `JsonNode`, so a new event type needs no SDK upgrade; `getDataJson()` is the same as text. `identification.completed` and `visitor.suspect` carry the event's project as `data.project_id`; `identification.refused` carries it only when the refused request named a known key. Read it with `getData().path("project_id")`.

```java
switch (event.getType()) {
    case WebhookEventType.VISITOR_SUSPECT:
        review(event.getData().path("request_id").asText());
        break;
    case WebhookEventType.IDENTIFICATION_COMPLETED:
        record(event.getData());
        break;
    default:
        break; // a type added later: acknowledge it and move on
}
```

## First-party proxy

Content blockers refuse browser requests to third-party APIs, and a refused identification is a visitor never identified. Create a proxy in the dashboard (**Integration > Proxy keys**) and mount it at the path it issued, such as `/k7x2qm4a`, and those requests become first-party. The proxy key is what lets that hop declare the visitor's real address, origin and user agent; a public key alone never can, so nobody can spoof them.

`FingerlyProxy` holds every decision and speaks to no server API. One of the adapters mounts it.

```java
FingerlyProxy proxy = FingerlyProxy.builder()
    .proxyKey(System.getenv("FINGERLY_PROXY_KEY"))
    .path("/k7x2qm4a")
    .clientIp(request -> request.header("CF-Connecting-IP") != null
        ? request.header("CF-Connecting-IP")
        : request.remoteAddress())
    .build();
```

> **`clientIp` is the one thing you must get right.** Take the header your CDN or load balancer sets when it is there, and the connection's peer address otherwise. Trust that header only when traffic can reach your server through nothing but that CDN or load balancer. Never read the left-most `X-Forwarded-For` value: the visitor writes it.

`clientIp` must return exactly one IPv4 or IPv6 address. Anything else (null, empty, a list, a port, a host name) is a configuration error: the request is refused with a 500 saying "The client IP callback returned no valid address", nothing is sent upstream, and the mistake is logged once.

Set `path` in every setup, as the examples below do. The servlet filters accept a proxy without one where the container maps only that path to the filter, but then the container decides what reaches it, including paths it normalises, such as a trailing slash or a dot segment. With `path` set, the proxy compares the raw path itself and answers near misses with its own uncacheable `404`, or passes them to the application. The WebFlux adapter, which sees every request, requires it.

`upstream(...)` changes the API origin. It takes `https://host` with an optional port and nothing else: a path, query, fragment, credentials, whitespace or control byte is refused when the proxy is built. A plain `http` upstream is refused too, except for local development with `allowInsecureLoopback(true)` and a host of `127.0.0.1`, `localhost` or `[::1]`.

Then point the browser SDK at the same path:

```ts
const fingerly = await load({ apiKey: 'fly_pk_…', endpoints: '/k7x2qm4a' })
```

### Adapters

| Stack | Artifact | Class | Registered with |
| --- | --- | --- | --- |
| [Spring Boot 3 and 4, Tomcat 10+, Jetty 11+](#spring-boot-3-and-4-tomcat-10-jetty-11) | `fingerly-server-jakarta` | `io.fingerly.server.jakarta.FingerlyProxyFilter` | A `FilterRegistrationBean` on the proxy path |
| [Spring Boot 2 and older containers](#spring-boot-2-and-older-containers) | `fingerly-server-javax` | `io.fingerly.server.javaxservlet.FingerlyProxyFilter` | The same, from the `javaxservlet` package |
| [Reactive Spring](#reactive-spring) | `fingerly-server-webflux` | `io.fingerly.server.webflux.FingerlyProxyWebFilter` | A bean (`path` is required) |
| [Other servers](#other-servers) | `fingerly-server` | `FingerlyProxy.handle` and `handleAsync` | Your own glue |

### Spring Boot 3 and 4, Tomcat 10+, Jetty 11+

<details>
<summary>Configuration</summary>

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

</details>

The filter is asynchronous: the container thread is released while Fingerly answers, so a slow upstream cannot occupy the container's thread pool. Spring Boot registers filters with async support; in `web.xml`, add `<async-supported>true</async-supported>` to the filter, or the thread waits up to the proxy's timeout.

With Spring Security on the classpath, its filter chain runs first and answers the proxy's path itself with `401` or `403`, because the browser carries no session or CSRF token there. Register the proxy ahead of it, here with `registration.setOrder(Ordered.HIGHEST_PRECEDENCE)`, and under WebFlux with `@Order(Ordered.HIGHEST_PRECEDENCE)` on the filter bean.

### Spring Boot 2 and older containers

The same, from `io.fingerly.server.javaxservlet`. The package is not `...javax`, because `javax` is a restricted package name on the module path.

### Reactive Spring

<details>
<summary>Configuration</summary>

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

</details>

Nothing in the filter blocks. The body is read reactively and the upstream call runs through `FingerlyProxy.handleAsync`, so no thread waits while Fingerly answers; only starting the call runs on `boundedElastic`, for the moment it takes. A visitor who disconnects cancels the upstream call on JDK 16 and newer; before that it ends at the deadline.

### Other servers

Build a `ProxyRequest` (method, raw path, headers, body, `remoteAddress`) and write the `ProxyResponse` back. `handleAsync` returns a `CompletableFuture` that always completes normally within the timeout; cancel it when the visitor disconnects. `handle` is the same, waiting on the calling thread.

### What the proxy does

Browser and proxied native client SDKs post a single `v: 3` identification envelope to that one path, and the proxy forwards every `POST` it answers with its raw request body bytes untouched to Fingerly's one ingest URL, `https://api.fingerly.io/api/v1/ingest` (`apiVersion` changes only the version in it). Any method other than `POST` (plus `OPTIONS` when `allowedOrigins(...)` is configured) gets `405`, with `Cache-Control: no-store`. Methods are compared exactly, so `post` gets `405` too. Event reads need a secret key and stay unreachable from a browser by construction.

With `path` set, the proxy answers exactly that path, compared with the whole raw request path including any context path, case-sensitively. A trailing or doubled slash, a dot segment, percent encoding or a path parameter makes a different path: it gets `404`, or passes through to the application under a filter, as does anything under the path (including retired `/supplement` subpaths). Without `path`, it answers whatever the framework routed to it. `path` must be a plain absolute path such as `/k7x2qm4a`.

| Situation | Answer |
| --- | --- |
| Path other than the configured one | `404`, or passed through to the application under a filter |
| Method other than `POST` (or `OPTIONS` when `allowedOrigins(...)` is configured) | `405` |
| Missing `X-Api-Key`, a secret key, or a public key from a different environment than the proxy key | `401` |
| Neither an `Origin` nor a validated `X-Fingerly-SDK-Platform` | `400` (`missing_origin`) |
| Body over 1 MiB (enforced while reading rather than after buffering) | `413` |
| Upstream did not answer within `timeout()` (5 seconds by default) | `504` ("Gateway timeout") |
| Anything else: refused, reset, truncated, larger than 4 MiB, compressed in a way that cannot be decoded, a redirect (never followed), or a status outside 200 to 599 | `502` ("Bad gateway") |
| `clientIp` returns no valid address | `500` ("The client IP callback returned no valid address") |

The proxy key (`fly_px_{production|staging|development}_…`) is required: `build()` throws `IllegalArgumentException` when it is missing or malformed. On every request the caller's `X-Api-Key` must be a public key (`fly_pk_…`) from the same environment as the proxy key. Anything else, a missing header, another environment's key or a secret `fly_sk_` key, gets a plain-text `401` with `Cache-Control: no-store`, and nothing is forwarded. Upstream receives the proxy key as `X-Fingerly-Proxy-Key` and the caller's public key as `X-Api-Key`; a secret key is never sent.

Incoming headers follow the sanitize-and-restore sequence: all incoming `X-Fingerly-*`, `Cookie` and `Authorization` headers are stripped, restoring `X-Fingerly-SDK-Platform` only if it matches an exact lowercase native or bridge platform (`ios`, `android`, `react-native`, `react-native-ios`, `react-native-android`, `flutter`, `flutter-ios`, `flutter-android`, `lynx-ios`, `lynx-android`, `capacitor`, `kotlin-multiplatform`). When both `Origin` and a validated `X-Fingerly-SDK-Platform` are absent, the request is rejected locally with `400` (`missing_origin`), and `X-Fingerly-Origin` is never synthesized from `Host`, `Referer` or the request URL. A request carries one channel upstream: with an `Origin`, the proxy sends `X-Fingerly-Origin` and drops the platform header, as the API does on a direct call; without one, it sends the validated `X-Fingerly-SDK-Platform`.

Alongside verified proxy metadata (`X-Fingerly-Host` included), only `Content-Type`, `X-Api-Key`, `User-Agent`, `Idempotency-Key`, `Sec-Fetch-*` and `Sec-CH-UA-*` are forwarded; customer cookies and authorization headers never are, and a visitor header that could not be forwarded is dropped. The proxy reports itself upstream as `java/0.4.1`, which the dashboard shows per proxy.

Only `Content-Type`, `X-Request-Id`, `RateLimit-Limit`, `RateLimit-Remaining` and `Retry-After` are relayed back, plus `Cache-Control: no-store`, so nothing on the response names Fingerly. The answer is read whole before anything is relayed, so a truncated one is never passed on as a success.

Every refusal and failure answer carries `Cache-Control: no-store`. One line is logged per failure, naming only the kind of failure, never the upstream address, a stack trace or a key, to the `System.Logger` named `io.fingerly.server.FingerlyProxy` at `WARNING` (which reaches SLF4J, Logback or Log4j through their usual bridges), or to your own `log(...)` callback.

Every adapter asks `proxy.handles(path)` before reading anything. A filter may be mapped more broadly than the one path the proxy owns, and calling `handle()` to find out would have consumed a request body the application still needs.

## Development and release preparation

```sh
./gradlew build                                        # compile, test (Spring 5.3 and 7), javadoc, jars
FINGERLY_CONTRACT_DIR=../../contract java tools/SyncContract.java
```

The build runs on a Java 17 toolchain and emits Java 11 bytecode with `--release`, so a method that exists only on a newer JDK cannot slip in unnoticed. Warnings are errors: a warning the SDK ships is one its users cannot fix.

The vendored synthetic contract snapshot makes tests independent of sibling repositories. Its hashes are checked by `ContractTest`, so there is no separate check step. Never put production secrets in fixtures.

<details>
<summary>Source structure</summary>

`FingerlyClient` holds the options; `EventsService` owns the reads and the list filters, and `Event`, `EventPage` and `EventListParams` are what they speak in. It also verifies and consumes events, with `VerifyOptions`, `VerifyResult`, `ConsumeResult` and `EventAlreadyConsumedException`. `DeviceCheckService` spends an event's DeviceCheck token, with `DeviceCheckBits`, `DeviceCheckWrite` and `DeviceCheckWriteResult`. `Webhooks` verifies signatures. `FingerlyProxy` holds every proxy decision, with `ProxyRequest` and `ProxyResponse` as the neutral shapes the adapters translate to. `SharedHttp` is the process-wide connection pool, `BoundedBody` reads every answer within its limit, and `Deadlines` is the one timer thread behind every deadline. `Keys` validates every caller-supplied option before any network access, and `Json` keeps the Jackson configuration in one place so a caller's own `ObjectMapper` cannot change how responses are read.

</details>

## Requirements

- Java 11 or newer, and Kotlin and Scala through the same artifacts. CI builds on Java 17 and 21.
- The only runtime dependency is `jackson-databind`.
- Servlet APIs and Spring are `provided` by your container, for the adapter artifacts.

## Documentation

- [Java SDK guide](https://docs.fingerly.io/docs/sdks/java)
- [Reading events](https://docs.fingerly.io/docs/reading-events) and [Webhooks](https://docs.fingerly.io/docs/webhooks)
- [Proxy integrations](https://docs.fingerly.io/docs/proxy-integrations)
- [API reference](https://docs.fingerly.io/reference/overview)

## Help

Open an issue on this repository, or write to [support@fingerly.io](mailto:support@fingerly.io). Security issues: [security@fingerly.io](mailto:security@fingerly.io).

## Licence

Apache-2.0. See [LICENSE](https://github.com/fingerly-io/sdk-java/blob/main/LICENSE).
