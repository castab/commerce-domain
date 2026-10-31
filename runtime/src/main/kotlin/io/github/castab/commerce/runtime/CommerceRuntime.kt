package io.github.castab.commerce.runtime

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.authorization.AuthorizationDirectory
import io.github.castab.commerce.runtime.authorization.PermissionCatalog
import io.github.castab.commerce.runtime.authorization.commercePermissionDefinitions
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.http.CommerceErrorHandling
import io.github.castab.commerce.runtime.http.QuiescingSseServer
import io.github.castab.commerce.runtime.http.healthRoutes
import io.github.castab.commerce.runtime.http.nativeSseServer
import io.github.castab.commerce.runtime.outbox.Outbox
import io.github.castab.commerce.runtime.persistence.ApplicationMigrations
import io.github.castab.commerce.runtime.persistence.AuthorizationRepository
import io.github.castab.commerce.runtime.persistence.FinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.PaymentRepository
import io.github.castab.commerce.runtime.persistence.PostgresFinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.PostgresPaymentRepository
import io.github.castab.commerce.runtime.persistence.PostgresPrincipalSessionRepository
import io.github.castab.commerce.runtime.persistence.ServiceCredentialRepository
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.persistence.isReachable
import io.github.castab.commerce.runtime.serviceauth.PersistentServiceCredentials
import io.github.castab.commerce.runtime.serviceauth.ServiceAccessTokens
import io.github.castab.commerce.runtime.serviceauth.ServiceCredentials
import io.github.castab.commerce.runtime.serviceauth.SignedServiceAccessTokens
import io.github.castab.commerce.runtime.session.PersistentSessionManager
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.PermissionDefinition
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.RoutingSseHandler
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.JettyLoom
import org.http4k.server.asServer
import org.jdbi.v3.core.Jdbi

private val logger = KotlinLogging.logger {}

/**
 * The shared runtime pieces an application may build on: configuration, the transaction
 * boundary, commerce-owned [financialDocumentRepository],
 * [paymentRepository], [financialLedger] operations, authenticated principal [sessions],
 * live [authorization] directory, and transaction-aware [outbox]. Application repositories use the same [transactor] and
 * [io.github.castab.commerce.runtime.persistence.Transaction] the runtime uses.
 *
 * [sessions] is how an application that has verified its own credentials starts a session
 * for the resulting `PrincipalId`, and how its routes authenticate later requests (see
 * `sessionAuthentication`). The runtime never sees the credentials themselves.
 *
 * The repositories participate in caller-owned transactions, including transactions that
 * also write application data. The runtime has no customer or other application
 * data model, so relationships between application entities and commerce facts stay in
 * application repositories.
 *
 * [serviceCredentials] administers the long-lived credentials of SERVICE principals, and
 * [serviceAccessTokens] exchanges them for short-lived bearer tokens and resolves those
 * tokens on later requests (see `ServiceAccessTokenAuthenticator`). Unlike human users,
 * whose credentials the application owns, service authentication is runtime-owned so that
 * every consuming application authenticates its services the same way. Both were added for
 * a backend-for-frontend that calls a commerce application as a service principal.
 *
 * Part of the provisional application-extension seam; see [ApplicationContributions].
 */
class CommerceRuntimeContext internal constructor(
    val configuration: CommerceRuntimeConfiguration,
    val transactor: Transactor,
    val financialDocumentRepository: FinancialDocumentRepository,
    val paymentRepository: PaymentRepository,
    val financialLedger: FinancialLedger,
    val sessions: SessionManager,
    val authorization: AuthorizationDirectory,
    val serviceCredentials: ServiceCredentials,
    private val configuredServiceAccessTokens: ServiceAccessTokens?,
    /** Transaction-aware integration event persistence, added for fionas-commerce. */
    val outbox: Outbox,
) {
    /**
     * Service access tokens. Available only when `serviceTokens` (its signing key) is
     * configured; otherwise reading it fails with [IllegalStateException], so an application
     * that composes service authentication without a signing key fails at startup rather than
     * running with a weaker fallback.
     */
    val serviceAccessTokens: ServiceAccessTokens
        get() =
            checkNotNull(configuredServiceAccessTokens) {
                "Service access tokens are not configured; set serviceTokens.signingKey (SERVICE_TOKENS_SIGNING_KEY)"
            }
}

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
 * @property migrations The application's own migrations: the PostgreSQL schema it owns, which
 *   also holds its Flyway history, and their locations, and only those. `null` when the
 *   application has none. commerce-runtime discovers its own migrations itself and always
 *   applies them first; the application's are a separate stream with its own schema history
 *   and version space. See [ApplicationMigrations] and [MigrationLifecycle].
 * @property routes The application's own routes, built from the shared
 *   [CommerceRuntimeContext]. They are served behind the same error handling as the
 *   commerce routes.
 * @property permissionDefinitions Software-defined application permissions added to the
 *   runtime's built-in `commercePermissionDefinitions` to form the running application's
 *   `PermissionCatalog` (`context.authorization.permissionCatalog`). A key defined twice,
 *   by the application or by both contributors, fails composition before anything starts.
 * @property backgroundServices Factories constructed after routes, without starting work.
 *   Services start with the runtime and close in reverse order before its pool. Added for
 *   fionas-commerce's opt-in outbox dispatcher; factories clean up their own failed construction.
 * @property sseRoutes Native SSE routes on the same listener, built from the same context.
 *   Use `sseHandshake` to apply existing HTTP authentication/authorization filters before
 *   streaming. Consumers register bounded application-owned work and return promptly.
 */
class ApplicationContributions(
    val migrations: ApplicationMigrations? = null,
    val routes: (CommerceRuntimeContext) -> List<RoutingHttpHandler> = { emptyList() },
    val permissionDefinitions: List<PermissionDefinition> = emptyList(),
    val backgroundServices: List<(CommerceRuntimeContext) -> ManagedBackgroundService> = emptyList(),
    val sseRoutes: (CommerceRuntimeContext) -> List<RoutingSseHandler> = { emptyList() },
)

/**
 * The commerce runtime of one concrete application: its HTTP handler, the Jetty server
 * serving it, and the connection pool.
 *
 * The runtime manages these resources; it does not own the process. [start] starts Jetty
 * and contributed background services and returns immediately. [close] stops admission,
 * closes services in reverse order, and closes the pool after the server stops. With SSE,
 * services cancel writers before Jetty closes streams. Blocking,
 * shutdown hooks, and the rest of the process lifecycle belong to the application's own
 * `main`.
 */
class CommerceRuntime internal constructor(
    /** The complete HTTP handler, usable without a server, for example in tests. */
    val http: HttpHandler,
    private val server: Http4kServer,
    private val dataSource: HikariDataSource,
    private val backgroundServices: List<ManagedBackgroundService> = emptyList(),
) : AutoCloseable {
    private var started = false
    private var closed = false

    /** Starts HTTP and contributed services once. A failed start closes all resources. */
    @Synchronized
    fun start(): CommerceRuntime {
        check(!closed) { "Commerce runtime is closed" }
        if (started) return this
        try {
            server.start()
            backgroundServices.forEach { it.start() }
            started = true
            logger.info { "event=server_started port=${server.port()}" }
            return this
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /** The port being served; the actual port after [start] when the configured port is 0. */
    fun port(): Int = server.port()

    /** Stops admission and resources, with the pool last, even if cleanup fails. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        logger.info { "event=runtime_stopping" }
        closeRuntimeResources(server, backgroundServices, dataSource)
        logger.info { "event=runtime_stopped" }
    }
}

/**
 * Composes the commerce runtime for a concrete application.
 *
 * Composition first validates [configuration] ([CommerceRuntimeConfiguration.validate]),
 * before it opens the connection pool or any other resource, whether the configuration was
 * loaded or constructed in code. An invalid configuration fails with
 * [IllegalArgumentException] naming the setting.
 *
 * It then runs the migration phase ([MigrationLifecycle]), before anything else
 * is built. With `migrations.onStartup = MIGRATE`, the runtime migrations and then the
 * application migrations are applied. With `VALIDATE`, the default, they are expected to
 * have been applied by a separate migration step, and the phase only validates that both
 * streams are fully applied. Either way, a failure throws Flyway's exception and closes the
 * pool: no runtime is returned, so there is no server to start against a database that is
 * not compatible.
 *
 * Every other dependency is then constructed here, in order, with ordinary Kotlin: Jdbi,
 * the transaction boundary, the session manager, the runtime's infrastructure routes
 * (`/health`, `/ready`, explicitly public) and the [application] routes, the http4k
 * handler, and Jetty. There is no dependency injection container, annotation scanning, or
 * reflection.
 *
 * [application] has no default: the runtime is not an application by itself, and the
 * caller decides what its application contributes. The server is created but not
 * started; call [CommerceRuntime.start].
 */
fun commerceRuntime(
    configuration: CommerceRuntimeConfiguration,
    application: ApplicationContributions,
): CommerceRuntime {
    configuration.validate()
    val permissionCatalog = PermissionCatalog.of(commercePermissionDefinitions, application.permissionDefinitions)
    val dataSource = createDataSource(configuration.database)
    val backgroundServices = mutableListOf<ManagedBackgroundService>()
    var server: Http4kServer? = null
    try {
        val migrations = MigrationLifecycle(dataSource, application.migrations)
        when (configuration.migrations.onStartup) {
            OnStartup.MIGRATE -> migrations.migrate()
            OnStartup.VALIDATE -> migrations.validate()
        }

        val jdbi = Jdbi.create(dataSource)
        val transactor = Transactor(jdbi)
        val authorizationRepository = AuthorizationRepository()
        transactor.inTransaction { permissionCatalog.validatePersisted(authorizationRepository.storedPermissionKeys(it)) }
        val sessions =
            PersistentSessionManager(
                transactor,
                PostgresPrincipalSessionRepository(),
                authorizationRepository,
                configuration.sessions.lifetime,
            )
        val authorization = AuthorizationDirectory(transactor, authorizationRepository, sessions, permissionCatalog)
        val serviceCredentials = PersistentServiceCredentials(transactor, ServiceCredentialRepository(), authorizationRepository)
        val serviceAccessTokens =
            configuration.serviceTokens?.let {
                SignedServiceAccessTokens(it, serviceCredentials, transactor, authorizationRepository)
            }
        val financialDocuments = PostgresFinancialDocumentRepository()
        val payments = PostgresPaymentRepository(financialDocuments)
        val context =
            CommerceRuntimeContext(
                configuration,
                transactor,
                financialDocuments,
                payments,
                FinancialLedger(transactor, financialDocuments, payments),
                sessions,
                authorization,
                serviceCredentials,
                serviceAccessTokens,
                Outbox(transactor),
            )

        val runtimeRoutes = listOf(healthRoutes(ready = { dataSource.isReachable() }))
        val http = CommerceErrorHandling.then(routes(*(runtimeRoutes + application.routes(context)).toTypedArray()))
        val sseRoutes = application.sseRoutes(context)
        server =
            if (sseRoutes.isEmpty()) {
                http.asServer(JettyLoom(configuration.server.port))
            } else {
                nativeSseServer(http, sseRoutes, configuration.server.port)
            }
        application.backgroundServices.forEach { backgroundServices.add(it(context)) }
        return CommerceRuntime(http, server, dataSource, backgroundServices.toList())
    } catch (e: Throwable) {
        try {
            closeRuntimeResources(server, backgroundServices, dataSource)
        } catch (cleanup: Throwable) {
            e.addSuppressed(cleanup)
        }
        throw e
    }
}

private fun closeRuntimeResources(
    server: Http4kServer?,
    services: List<ManagedBackgroundService>,
    dataSource: HikariDataSource,
) {
    var failure: Throwable? = null

    fun release(action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
    }
    if (server is QuiescingSseServer) {
        release { server.stopAccepting() }
        services.asReversed().forEach { service -> release { service.close() } }
        release { server.stop() }
    } else {
        release { server?.stop() }
        services.asReversed().forEach { service -> release { service.close() } }
    }
    release { dataSource.close() }
    failure?.let { throw it }
}
