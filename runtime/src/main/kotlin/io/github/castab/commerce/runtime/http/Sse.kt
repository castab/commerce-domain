package io.github.castab.commerce.runtime.http

import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.PolyHandler
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.routing.RoutingSseHandler
import org.http4k.routing.sse
import org.http4k.server.Http4kServer
import org.http4k.server.JettyLoom
import org.http4k.server.asServer
import org.http4k.sse.SseHandler
import org.http4k.sse.SseResponse
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies an HTTP handshake filter (for example `AccessControl.requirePermission`) once,
 * before preparing a native stream. Mount the result in [ApplicationContributions.sseRoutes][io.github.castab.commerce.runtime.ApplicationContributions.sseRoutes].
 *
 * The filter must call its next handler to accept. A short-circuit response is returned
 * over ordinary HTTP, including its headers and body. Exceptions from the filter or
 * [prepare] pass through [CommerceErrorHandling]. The authorized request reaches [prepare],
 * so its principal can be used there. Complete validation and short transactions there;
 * only register live work in the returned response's consumer, after commitment.
 * Capture needed authorized values in that closure; `stream.connectRequest` is the
 * transport request, not the request decorated by the handshake filter.
 *
 * This adapts handshake filters only, not filters that transform a successful response body.
 */
fun sseHandshake(
    filter: Filter,
    prepare: SseHandler,
): SseHandler =
    { request ->
        var prepared: SseResponse? = null
        val response =
            filter.then { authorized ->
                prepared = prepare(authorized)
                Response(Status.OK)
            }(request)
        if (prepared == null || response.status != Status.OK) throw SseRejection(response)
        prepared
    }

private class SseRejection(
    val response: Response,
) : RuntimeException(null, null, false, false)

private val sseLogger = KotlinLogging.logger {}

/** Stops admission before application services cancel writers and native Jetty closes streams. */
internal interface QuiescingSseServer : Http4kServer {
    fun stopAccepting()
}

/**
 * Adapter-specific bridge for http4k 6.58.0.0. Jetty's Handler.Sequence invokes the SSE
 * matcher and, when handled=false, the HTTP handler synchronously on the SAME thread.
 * Only the already-normalized rejection response crosses that call boundary; no principal,
 * subscription or request is stored. HTTP removes it before doing anything else. It is
 * never set for an accepted stream or an unmatched route. Real-socket tests pin this
 * dependency: re-audit when upgrading the adapter. No request is authenticated twice.
 */
internal fun nativeSseServer(
    http: HttpHandler,
    routes: List<RoutingSseHandler>,
    port: Int,
): Http4kServer {
    val rejected = ThreadLocal<Response>()
    val accepting = AtomicBoolean(true)
    val routing = sse(routes)
    val native: SseHandler = native@{ request ->
        rejected.remove()
        if (!accepting.get() || request.method != Method.GET || request.uri.path in setOf("/health", "/ready")) {
            return@native SseResponse(Status.NOT_FOUND, handled = false) {}
        }
        var prepared: SseResponse? = null
        val response =
            CommerceErrorHandling.then {
                try {
                    prepared = routing(it)
                    Response(Status.OK)
                } catch (rejection: SseRejection) {
                    rejection.response
                }
            }(request)
        val selected = prepared
        if (response.status != Status.OK || selected == null) {
            // Also preserves successful HTTP short-circuits (e.g. 204) without opening a stream.
            rejected.set(response)
            SseResponse(Status.NOT_FOUND, handled = false) {}
        } else if (selected.handled && selected.status != Status.OK) {
            val category = ErrorCategory.entries.firstOrNull { it.status == selected.status }
            rejected.set(
                (category?.let { errorResponse(it, it.status.description) } ?: Response(selected.status))
                    .headers(selected.headers.filterNot { it.first.equals("Content-Type", ignoreCase = true) }),
            )
            SseResponse(Status.NOT_FOUND, handled = false) {}
        } else {
            selected.withConsumer { stream ->
                try {
                    selected.consumer(stream)
                } catch (failure: Exception) {
                    sseLogger.error(failure) { "event=sse_consumer_failed" }
                    stream.close()
                }
            }
        }
    }
    val fallback: HttpHandler = { request ->
        val response = rejected.get()
        rejected.remove()
        if (!accepting.get()) Response(Status.SERVICE_UNAVAILABLE) else response ?: http(request)
    }
    val server = PolyHandler(http = fallback, sse = native).asServer(JettyLoom(port))
    return object : QuiescingSseServer, Http4kServer by server {
        override fun stopAccepting() {
            accepting.set(false)
        }
    }
}
