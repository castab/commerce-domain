package io.github.castab.commerce.service.runtime

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.service.config.CommerceServiceConfiguration
import io.github.castab.commerce.service.customer.CreateCustomer
import io.github.castab.commerce.service.customer.CustomerRepository
import io.github.castab.commerce.service.customer.GetCustomer
import io.github.castab.commerce.service.customer.customerRoutes
import io.github.castab.commerce.service.http.CommerceErrorHandling
import io.github.castab.commerce.service.http.healthRoutes
import io.github.castab.commerce.service.persistence.DatabaseMigrations
import io.github.castab.commerce.service.persistence.Transactor
import io.github.castab.commerce.service.persistence.createDataSource
import io.github.castab.commerce.service.persistence.isReachable
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
 * The shared runtime pieces an application may build on: the transaction boundary and the
 * commerce repositories. Application code that must write its own tables and commerce
 * tables atomically uses the same [transactor] and passes one transaction to both.
 *
 * Part of the provisional application-extension seam; see [ApplicationContributions].
 */
class CommerceRuntime internal constructor(
    val configuration: CommerceServiceConfiguration,
    val transactor: Transactor,
    val customers: CustomerRepository,
)

/**
 * What a concrete application adds to the generic commerce runtime.
 *
 * This is deliberately small and not booking-specific. It is the seam through which
 * application-owned capabilities (including a future, strongly typed booking extension)
 * plug into the shared runtime without forking it.
 *
 * Together with [CommerceRuntime], this is the provisional application-extension seam, not
 * a settled contract: it is expected to change once the booking extension and further
 * capabilities are designed from real consumer requirements, and it grows only when a
 * concrete consumer needs it.
 *
 * @property migrationLocations Flyway locations of the application's own migrations, run
 *   after the commerce migrations. See [DatabaseMigrations].
 * @property routes The application's own routes, built from the shared [CommerceRuntime].
 *   They are served behind the same error handling as the commerce routes.
 */
class ApplicationContributions(
    val migrationLocations: List<String> = emptyList(),
    val routes: (CommerceRuntime) -> List<RoutingHttpHandler> = { emptyList() },
)

/**
 * A composed commerce service: its HTTP handler, the Jetty server serving it, and the
 * connection pool. [close] stops the server and closes the pool.
 */
class CommerceService internal constructor(
    /** The complete HTTP application, usable without a server, for example in tests. */
    val http: HttpHandler,
    private val server: Http4kServer,
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    fun start(): CommerceService {
        server.start()
        logger.info { "event=server_started port=${server.port()}" }
        return this
    }

    /** The port being served; the actual port after [start] when the configured port is 0. */
    fun port(): Int = server.port()

    override fun close() {
        logger.info { "event=server_stopping" }
        server.stop()
        dataSource.close()
        logger.info { "event=server_stopped" }
    }
}

/**
 * The composition root. Every dependency is constructed here, in order, with ordinary
 * Kotlin: configuration, DataSource, Jdbi, transactions and repositories, application
 * operations, HTTP routes, the http4k application, and Jetty. There is no dependency
 * injection container, annotation scanning, or reflection.
 *
 * The server is created but not started; call [CommerceService.start].
 */
fun commerceService(
    configuration: CommerceServiceConfiguration,
    application: ApplicationContributions = ApplicationContributions(),
): CommerceService {
    val dataSource = createDataSource(configuration.database)
    try {
        if (configuration.flyway.enabled) {
            logger.info { "event=flyway_migrate" }
            DatabaseMigrations(dataSource, application.migrationLocations).migrate()
        }

        val jdbi = Jdbi.create(dataSource)
        val transactor = Transactor(jdbi)
        val customers = CustomerRepository()
        val runtime = CommerceRuntime(configuration, transactor, customers)

        val createCustomer = CreateCustomer(transactor, customers)
        val getCustomer = GetCustomer(transactor, customers)

        val commerceRoutes =
            listOf(
                healthRoutes(ready = { dataSource.isReachable() }),
                customerRoutes(createCustomer, getCustomer),
            )
        val http = CommerceErrorHandling.then(routes(*(commerceRoutes + application.routes(runtime)).toTypedArray()))
        val server = http.asServer(JettyLoom(configuration.server.port))
        return CommerceService(http, server, dataSource)
    } catch (e: Exception) {
        dataSource.close()
        throw e
    }
}
