package io.github.castab.commerce.runtime.http

import kotlinx.serialization.Serializable
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes

@Serializable
data class HealthResponse(
    val status: String,
)

private val healthBody = jsonBody(HealthResponse.serializer())

/**
 * `GET /health` is liveness: it answers whenever the process serves HTTP and touches no
 * dependency, so an unreachable database never makes a platform restart the application.
 *
 * `GET /ready` is readiness: it answers `503` while [ready] is false, for example while
 * the database is unreachable.
 */
fun healthRoutes(ready: () -> Boolean): RoutingHttpHandler =
    routes(
        "/health" bind Method.GET to { Response(Status.OK).with(healthBody of HealthResponse("ok")) },
        "/ready" bind Method.GET to {
            if (ready()) {
                Response(Status.OK).with(healthBody of HealthResponse("ready"))
            } else {
                Response(Status.SERVICE_UNAVAILABLE).with(healthBody of HealthResponse("unavailable"))
            }
        },
    )
