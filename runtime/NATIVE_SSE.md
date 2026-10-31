# Native SSE: adapter decision and adoption

## Decision

Use the existing JettyLoom listener and `PolyHandler(http, sse)` with native http4k
`RoutingSseHandler`, `SseResponse`, `Sse`, and `SseMessage`. The required types live in
`http4k-realtime-core`, now an explicit `api` dependency at **6.58.0.0**. The runtime
remains a library and the domain remains unchanged.

The pinned binary's `JettyEventStreamHandler` checks the Accept header, invokes the SSE
handler, then commits headers and activates the consumer only when `handled` is true.
Its `SseResponse` carries no HTTP body. `Jetty.toServer` builds a synchronous
`Handler.Sequence`: SSE first, then ordinary HTTP. The sequence includes a blocking HTTP
handler, so JettyLoom dispatches preparation on a virtual thread (asserted by the socket
tests). The consumer runs after commitment and must return promptly; do not assume it
runs on the handshake's virtual thread.

`sseHandshake(filter, prepare)` lets an application use its existing HTTP authentication
and permission filters. A private control exception carries a filter's short-circuit
response to the runtime adapter. The adapter evaluates preparation under the existing
`CommerceErrorHandling`. Rejections are stored only for the synchronous fall-through to
HTTP and removed before the HTTP handler returns them. This internal, server-local
`ThreadLocal<Response>` is not identity storage, a request cache, or application state;
accepted streams and unmatched routes never store an entry. Every new SSE dispatch also
clears the slot defensively. No handler is evaluated twice, and there is no duplicate
error serializer. Do not insert asynchronous dispatch or an intermediate handler between
these two adapter calls. Re-audit this bridge and run the real-socket tests on upgrades.

An unmatched native route falls through normally. A handled native response whose status
is not 200 is returned through HTTP without invoking its consumer; known error statuses
use `errorResponse`, while other statuses remain bodyless. Handshake filter responses
retain their original bodies and headers. The helper is for handshake filters, not
response-body transformation or compression. Successful stream headers belong on
`SseResponse`. The runtime limits native dispatch to GET; other methods retain HTTP routing
semantics. Accept matching otherwise follows the pinned adapter.

Rejected alternatives: manually framed streaming HTTP bodies recreate the starvation
failure; native error statuses alone lose JSON bodies; rerunning a shared handshake in an
HTTP fallback duplicates authentication and can observe different state; a second server
changes lifecycle ownership; a custom Jetty protocol adapter duplicates native machinery.
The narrow fallback bridge keeps the native adapter and pins its dependency with tests.

## Shutdown and callback contract

For a runtime with SSE routes, `close()` gates new HTTP/SSE work with 503, closes contributed
services in reverse order, stops Jetty, then closes the pool. HTTP-only shutdown is unchanged.
This order matters: the native emitter serializes close and send with a lock, so calling
Jetty stop before the application's service cancels a blocked writer can delay shutdown.
The real test fills a non-reading socket, observes the writer blocked inside Jetty, proves
another subscriber and HTTP still progress, then cancels/joins that writer through the
managed-service lifecycle before stopping the server within ten seconds.

Applications must provide bounded cancellation and cleanup, including a bounded join of
writers before the service's `close()` returns. Runtime cannot make arbitrary application
callbacks or uninterruptible work bounded. Use one bounded writer per admitted subscription
or an equivalently isolated design, never a serial shared socket publisher. Native stream
close is idempotent. Jetty closes remaining streams on server stop, including idle streams;
the runtime adds no connection registry or notification executor.

Register `stream.onClose { ... }` promptly to remove the subscription. Native disconnect
detection is driven by failed writes (including the adapter's 15-second transport heartbeat),
not a guaranteed immediate notification of a vanished peer. Do not use this as an
authorization-expiry timer. The callback must be prompt and must not join its own writer.
Initial consumer exceptions are logged and terminate the stream; later scheduled tasks
must catch failures and close themselves. No error event or revised HTTP status is sent
after commitment. Never hold a transaction for the lifetime of a subscription.

## Downstream handoff

This API is **not in 0.0.26**. Consume `io.github.castab:commerce-runtime:<release-containing-this-PR>`
once the maintainer publishes it; both artifacts retain the same tag-derived version. No
release number is assigned or tag published by this change. If the next release is
`v0.0.27`, the dependency is `io.github.castab:commerce-runtime:0.0.27`; verify that the
release contains this PR before changing the downstream pin. Do not independently upgrade
http4k. Local builds use the existing `0.0.0-SNAPSHOT` convention.

1. Supply `sseRoutes: (CommerceRuntimeContext) -> List<RoutingSseHandler>` as the last
   optional constructor parameter of `ApplicationContributions`. Existing HTTP routes and
   background-service contributions continue to work. See the complete neutral mounting
   example in [README](README.md#native-server-sent-events).
2. Import `org.http4k.routing.sse.bind` (alias it if HTTP `bind` is also imported), and mount
   `"/example/updates" bind sseHandshake(access.requirePermission(exampleRead)) { request -> ... }`.
   Compose additional existing permission filters as needed. Missing identity yields 401,
   insufficient permission yields 403, path lenses yield 400, `CommerceFailure.NotFound`
   yields 404, and unexpected preparation exceptions yield sanitized 500. No duplicate
   HTTP rejection route is required. Applications still own executable endpoint/OpenAPI
   declarations and their business resource checks.
3. In preparation, validate resources after authorization and capture any needed authorized
   values. The consumer's `stream.connectRequest` is the transport request, not the
   handshake-filter-decorated request. Do not allocate live subscriptions until the consumer
   is called. Failed handshakes never activate consumers.
4. In the consumer, register cleanup and bounded asynchronous work, then return. Writers use
   `stream.send(SseMessage.Event("changed", "payload", SseEventId("1")))`, native ping/retry
   messages when needed, and `stream.close()` to end the connection. Native framing and
   flushing are adapter-owned.
5. Contribute the writer/subscription owner as a `ManagedBackgroundService`; its start is
   prompt, and its close cancels and joins work before the shared pool closes. Handle
   registration racing shutdown by refusing work once the service is closed. Applications
   own capacity, coalescing, credentials/permissions revalidation, expiry and maximum
   lifetime, business polling/notifications, and reverse-proxy buffering/timeouts.

`NativeSseSpec` uses the actual `commerceRuntime` and PostgreSQL, real sockets, and deadlines.
It covers framing/IDs, delayed events and flushing, two live streams, HTTP/health/readiness,
blocked readers, handshake errors and no activation on rejection, callback failure, and
shutdown. Existing HTTP/authentication and lifecycle suites remain required alongside it.
