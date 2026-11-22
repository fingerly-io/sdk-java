# Changelog

## 0.5.0

- `AgentSightings` sees the AI crawlers and fetchers that never run a page's
  JavaScript. `FingerlyAgentSightingsFilter` (Jakarta and javax servlets) and
  `FingerlyAgentSightingsWebFilter` (WebFlux) report the requests your
  application served from a background daemon thread, never on the request
  thread, and `lookup` answers from memory what Fingerly said about each one,
  or `AgentSighting.UNKNOWN`. Memory is bounded, failures fail open, and
  `close()` sends what is still waiting. `fingerly.agents().reportSightings(...)`
  is the raw call.
- `Event.getAi()` reads the AI agent behind an `ai_bot` event: its name,
  provider, purpose, runtime, whether it named itself, and how its identity
  was verified, with the `AiPurpose`, `AiRuntime`, `AiVerificationMethod` and
  `AiVerificationResult` constants.
- `EventListParams.Builder` filters the event list by `kind()`, `aiProvider()`
  and `aiPurpose()`.
- `FingerlyProxy` and its adapters forward the Web Bot Auth `Signature`,
  `Signature-Input` and `Signature-Agent` headers, each dropped when over
  4096 bytes.
- `FingerlyApiException.getRateLimitScope()` names the limit behind a `429`.
- `Signal` matches the server's signal list, including `AI_SPOOFED` and
  `IDENTITY_OBFUSCATED`.
- An empty or non-object answer (an empty `200`, a `204`, a JSON `null`) is a
  `FingerlyException` from every read and from `Webhooks.parse`, never a
  `NullPointerException`.
- A present `AiAgent` never returns null from a getter: when an answer lacks
  one of its fields, `getAi()` is empty.
- `AgentSightingsResult.getItems()` never holds null. An answer without its
  `visitor_kind` or `ttl`, or one that does not fit the type, is
  `AgentSighting.UNKNOWN`, and the rest of the batch still counts.
- A `clientIp` or `log` callback that throws a checked exception, as Kotlin
  code can, never reaches your request: sightings fail open, and the proxy
  answers its usual `500`.
- The sightings thread keeps running whatever goes wrong in a flush, and a
  rate limit pauses reporting for at most a day, whatever its `Retry-After`.
- `AgentSightings.close()` does its work once when called concurrently.
- `FingerlyAgentSightingsFilter` reports an asynchronous request once, when
  its last async cycle completes, including one that starts a second cycle
  (a `DeferredResult` holding a `Callable`), and a filter mapped to the
  `ASYNC`, `ERROR`, `FORWARD` or `INCLUDE` dispatcher types reports nothing
  twice.
- A `User-Agent`, `Host` or Web Bot Auth header that arrived as UTF-8 is
  reported and keyed as UTF-8, although servlet containers and Netty decode
  header bytes as ISO-8859-1.
- `AgentSightings` never sends an item the API would refuse, with its whole
  batch: lengths are UTF-8 bytes as sent, an unpaired surrogate is sent as
  U+FFFD, a `Host` over 4096 bytes is left out, and a request whose method is
  over 4096 bytes is not reported.
- `VisitorKind.UNKNOWN` names the answer the API gives a reported request it
  could not use: ttl 0, so it is never remembered and stays
  `AgentSighting.UNKNOWN` to `lookup`, while the other requests of the batch
  are answered as usual. A `422` now means no request in the batch was
  usable.
- A `Retry-After` of ten or more digits reads as `Integer.MAX_VALUE` rather
  than as missing, so a rate limit asking for years pauses sightings for a
  day. A negative or unreadable one is still missing.
- The README and `DeviceCheckService` describe the two DeviceCheck tokens an
  identification holds, one to read and one to write, so a check-then-mark
  flow reads and writes with the same request ID.

## 0.4.1

- Each `AttestSkippedReason` constant describes when the iOS and Android
  SDKs send it.
- The vendored OpenAPI contract declares `enum` for the closed string
  fields. The SDK's own types are unchanged and stay open strings.

## 0.4.0

- Named constants for every closed string set the API sends: `Signal` (every
  signal group and detector), `SuspectLevel`, `Confidence`, `EventState`,
  `Environment`, `Platform`, `AnonymityNetwork`, `DeviceKind`, `VisitorKind`,
  `AttestationState`, `AttestationStatus`, `AttestSkippedReason`,
  `VerifyReason`, `WebhookEventType` and `WebhookEnvironment`. They are
  `String` constants, so they work in `switch`, and the accessors stay
  strings, so a value the server adds later still reads.
  `EventTrigger.SIGNAL_` and `VerifyResult.REASON_` are the same strings.
- `Signal` matches the server's signal list, including `repeated_reinstall`,
  `device_activity_high` and `device_activity_very_high`.
- `Event.getAnonymityNetwork()` documents `relay`, which the server already
  sends.

## 0.3.0

- Synced the vendored `v: 3` OpenAPI and fixture contract snapshot, added
  `Event.getReason()` and `EventTrigger` v3 signal constants, and documented
  empty `visitor_id` (`""`) on unidentifiable events.
- Enforced the `proxy.md` sanitize-and-restore contract across `FingerlyProxy`
  and all servlet/WebFlux adapters: validated `X-Fingerly-SDK-Platform`
  restoration after stripping `x-fingerly-*`, HTTP `400 missing_origin` when
  both `Origin` and a validated native platform header are absent (never
  synthesizing `X-Fingerly-Origin`), forwarding `Sec-Fetch-*` and `Sec-CH-UA-*`
  headers, and optional cross-origin CORS support.
- `FingerlyProxy` requires a `fly_px_` proxy key and checks the caller's
  public key on every request; a secret key is never sent upstream.
- `fingerly.deviceCheck().query(requestId)` and
  `fingerly.deviceCheck().write(requestId, DeviceCheckWrite)` (with
  `queryAsync` and `writeAsync`) read and write an iOS event's DeviceCheck
  bits. They retry the identical request only after a `503` or a transport
  failure, and `FingerlyApiException` names each DeviceCheck error code and
  `getAppleStatus()`.
- Added `fingerly.events().verify(requestId, VerifyOptions)` (with
  `verify(requestId)` and `verifyAsync`), which reads an event and checks it exists, is recent
  (`maxAgeSeconds`, 300 by default), carries the expected `tag`, is
  identifiable and scored, then consumes it once, and
  `fingerly.events().consume(requestId)` (with `consumeAsync`), which throws
  `EventAlreadyConsumedException` on `409 event_already_consumed`.

## 0.2.0

- `Event.getProjectId()` returns the Fingerly project an event belongs to, on
  single reads and list rows. It is null when the server does not send it.
- Webhook `data` for `identification.completed` and `visitor.suspect` (and
  `identification.refused` when the key was known) carries `project_id`; read
  it from `getData()`.
- Events carry `ip_network` (`getIpNetwork()`), an IPv4 /24 or IPv6 /40,
  instead of `ip_address`; the API no longer returns the address.
- `getDeferredSignals()` is removed: single-event reads no longer return the
  deferred report.
- `getDocument()` is an allow-listed view of the archived submission, without
  raw signals, user agent or address.
- The vendored contract snapshot is refreshed.

## 0.1.0

First release: event reads, webhook verification and the same-origin proxy,
with adapters for Jakarta Servlet, javax.servlet and Spring WebFlux.

Changes made before the first release, from the pre-release field test:

- The proxy is non-blocking: `FingerlyProxy.handleAsync` returns a
  `CompletableFuture`, the servlet filters use `AsyncContext` and the WebFlux
  filter uses `Mono.fromFuture`. The WebFlux filter no longer takes a
  scheduler.
- `events().getAsync()` and `listAsync()` read without blocking.
- An upstream timeout is `504 Gateway timeout`; any other upstream failure is
  `502 Bad gateway`; one line is logged per failure (`Builder.log`).
- Answers are capped at 4 MiB and read whole before being relayed; gzip and
  deflate answers are decoded.
- A deadline closes the upstream connection on JDK 11 to 15 as well.
- Under Reactor Netty, `remoteAddress()` is the socket's peer even with
  `server.forward-headers-strategy=native`, which would otherwise make it the
  left-most `X-Forwarded-For` a visitor writes.
- `jackson-databind` is 2.18.12, the current patch release of its line.
- `clientIp` must return exactly one IP address; `ProxyRequest.remoteAddress()`
  carries the connection's peer, filled by every adapter.
- The proxy path is matched exactly: a trailing slash is a different path.
- One shared HTTP/1.1 connection pool per process, recycled every two minutes
  for DNS.
- `FingerlyApiException.isPending()` for `404 event_pending`: the event was
  accepted and is still being recorded, so read again after
  `getRetryAfter()` seconds. `isEventNotFound()` still matches only
  `event_not_found` (never issued, or past retention).
- `FingerlyApiException.getRetryAfter()`, and the request id from
  `X-Request-Id` when the body has none; error bodies are never echoed.
- `getRawJson()`, `getDataJson()` and `getDetailsJson()` for applications on
  Jackson 3 (Spring Boot 4).
- Webhook verification reads at most 32 signature candidates.
- `toString()` of every SDK object redacts credentials.
- Every jar carries `META-INF/LICENSE` and an `Automatic-Module-Name`.
- Tested against Spring 5.3 and 7 (Spring Boot 2 to 4).
