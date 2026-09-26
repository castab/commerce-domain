package io.github.castab.commerce.runtime

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.CommerceErrorHandling
import io.github.castab.commerce.runtime.http.healthRoutes
import io.github.castab.commerce.runtime.persistence.DatabaseMigrations
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.persistence.isReachable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.JettyLoom
import org.http4k.server.asServer
import org.jdbi.v3.core.Jdbi

private val logger = KotlinLogging.logger {}

/**
 * The shared runtime pieces an application may build on: the configuration and the
 * transaction boundary. Application repositories use the same [transactor] and
 * [io.github.castab.commerce.runtime.persistence.Transaction] the runtime uses.
 *
 * The runtime provides the transaction boundary required for future atomic application
 * plus commerce writes. It owns no commerce repository yet, so no such cross-boundary write
 * exists today; commerce repositories join the context only when the runtime gains real
 * persistence for commerce-domain facts. The runtime has no customer or other application
 * data model, so relationships between application entities and commerce facts stay in
 * application repositories.
 *
 * Part of the provisional application-extension seam; see [ApplicationContributions].
 */
class CommerceRuntimeContext internal constructor(
    val configuration: CommerceRuntimeConfiguration,
    val transactor: Transactor,
)

/**
 * What a concrete application adds to the commerce runtime.
 *
 * This is deliberately small and not booking-specific. It is the seam through which
 * application-owned capabilities (including a future, strongly typed booking extension)
 * plug into the shared runtime without forking it. [commerceRuntime] requires it
 * explicitly: an application with nothing to add still states so, by passing
 * `ApplicationContributions()`.
 *
 * Together with [CommerceRuntimeContext], this is the provisional application-extension
 * seam, not a settled contract: it is expected to change once the booking extension and
 * further capabilities are designed from real consumer requirements, and it grows only
 * when a concrete consumer needs it.
 *
 * @property migrationLocations Flyway locations of the application's own migrations, run
 *   after the commerce migrations. See [DatabaseMigrations].
 * @property routes The application's own routes, built from the shared
 *   [CommerceRuntimeContext]. They are served behind the same error handling as the
 *   commerce routes.
 */
class ApplicationContributions(
    val migrationLocations: List<String> = emptyList(),
    val routes: (CommerceRuntimeContext) -> List<RoutingHttpHandler> = { emptyList() },
)

/**
 * The commerce runtime of one concrete application: its HTTP handler, the Jetty server
 * serving it, and the connection pool.
 *
 * The runtime manages these resources; it does not own the process. [start] starts Jetty
 * and returns immediately, and [close] stops the server and closes the pool. Blocking,
 * shutdown hooks, and the rest of the process lifecycle belong to the application's own
 * `main`.
 */
class CommerceRuntime internal constructor(
    /** The complete HTTP handler, usable without a server, for example in tests. */
    val http: HttpHandler,
    private val server: Http4kServer,
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    fun start(): CommerceRuntime {
        server.start()
        logger.info { "event=server_started port=${server.port()}" }
        return this
    }

    /** The port being served; the actual port after [start] when the configured port is 0. */
    fun port(): Int = server.port()

    override fun close() {
        logger.info { "event=runtime_stopping" }
        server.stop()
        dataSource.close()
        logger.info { "event=runtime_stopped" }
    }
}

/**
 * Composes the commerce runtime for a concrete application.
 *
 * Every dependency is constructed here, in order, with ordinary Kotlin: configuration,
 * DataSource, Flyway, Jdbi, the transaction boundary, the runtime's infrastructure routes
 * (`/health`, `/ready`) and the [application] routes, the http4k handler, and Jetty. There
 * is no dependency injection container, annotation scanning, or reflection.
 *
 * [application] has no default: the runtime is not an application by itself, and the
 * caller decides what its application contributes. The server is created but not
 * started; call [CommerceRuntime.start].
 */
fun commerceRuntime(
    configuration: CommerceRuntimeConfiguration,
    application: ApplicationContributions,
): CommerceRuntime {
    val dataSource = createDataSource(configuration.database)
    try {
        if (configuration.flyway.enabled) {
            logger.info { "event=flyway_migrate" }
            DatabaseMigrations(dataSource, application.migrationLocations).migrate()
        }

        val jdbi = Jdbi.create(dataSource)
        val transactor = Transactor(jdbi)
        val context = CommerceRuntimeContext(configuration, transactor)

        val runtimeRoutes = listOf(healthRoutes(ready = { dataSource.isReachable() }))
        val http = CommerceErrorHandling.then(routes(*(runtimeRoutes + application.routes(context)).toTypedArray()))
        val server = http.asServer(JettyLoom(configuration.server.port))
        return CommerceRuntime(http, server, dataSource)
    } catch (e: Exception) {
        dataSource.close()
        throw e
    }
}
