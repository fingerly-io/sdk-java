# Changelog

## 0.1.0 (unreleased)

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
