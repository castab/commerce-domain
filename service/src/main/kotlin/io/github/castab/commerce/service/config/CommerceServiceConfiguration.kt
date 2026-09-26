package io.github.castab.commerce.service.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.PropertySource

/**
 * Runtime configuration of a commerce service.
 *
 * Values come from the HOCON resource `application.conf` (commerce-service ships one with
 * every default; an application may put its own `application.conf` first on the
 * classpath) and are then overridden by environment variables. Secrets are supplied only
 * through the environment and are never committed.
 *
 * | Setting | Environment variable |
 * |---|---|
 * | `server.port` | `PORT` |
 * | `database.jdbcUrl` | `DATABASE_JDBC_URL` |
 * | `database.username` | `DATABASE_USERNAME` |
 * | `database.password` | `DATABASE_PASSWORD` |
 * | `database.maximumPoolSize` | `DATABASE_MAXIMUM_POOL_SIZE` |
 * | `database.minimumIdle` | `DATABASE_MINIMUM_IDLE` |
 * | `database.connectionTimeoutMs` | `DATABASE_CONNECTION_TIMEOUT_MS` |
 * | `database.validationTimeoutMs` | `DATABASE_VALIDATION_TIMEOUT_MS` |
 * | `flyway.enabled` | `FLYWAY_ENABLED` |
 */
data class CommerceServiceConfiguration(
    val server: Server = Server(),
    val database: Database,
    val flyway: Flyway = Flyway(),
) {
    data class Server(
        val port: Int = 8080,
    )

    data class Database(
        val jdbcUrl: String,
        val username: String,
        val password: String,
        val maximumPoolSize: Int = 4,
        val minimumIdle: Int = 1,
        val connectionTimeoutMs: Long = 500,
        val validationTimeoutMs: Long = 1000,
    ) {
        // The password never appears in logs or failure messages.
        override fun toString(): String =
            "Database(jdbcUrl=$jdbcUrl, username=$username, password=****, " +
                "maximumPoolSize=$maximumPoolSize, minimumIdle=$minimumIdle, " +
                "connectionTimeoutMs=$connectionTimeoutMs, validationTimeoutMs=$validationTimeoutMs)"
    }

    /** Whether the service applies migrations at startup. Off by default, as in production deployments that migrate separately. */
    data class Flyway(
        val enabled: Boolean = false,
    )

    /** Fails with [IllegalArgumentException], naming the environment variable, when a value is unusable. */
    fun validate() {
        require(server.port in 0..65535) { "PORT must be between 0 and 65535" }
        require(database.jdbcUrl.isNotBlank()) { "DATABASE_JDBC_URL is required" }
        require(database.jdbcUrl.startsWith("jdbc:postgresql:")) { "DATABASE_JDBC_URL must be a PostgreSQL JDBC URL" }
        require(database.username.isNotBlank()) { "DATABASE_USERNAME is required" }
        require(database.password.isNotBlank()) { "DATABASE_PASSWORD is required" }
        require(database.maximumPoolSize > 0) { "DATABASE_MAXIMUM_POOL_SIZE must be positive" }
        require(database.minimumIdle in 0..database.maximumPoolSize) {
            "DATABASE_MINIMUM_IDLE must be between zero and DATABASE_MAXIMUM_POOL_SIZE"
        }
        require(database.connectionTimeoutMs > 0) { "DATABASE_CONNECTION_TIMEOUT_MS must be positive" }
        require(database.validationTimeoutMs > 0) { "DATABASE_VALIDATION_TIMEOUT_MS must be positive" }
    }

    companion object {
        /**
         * Loads [resource] from the classpath, applies [environment] overrides, and validates.
         *
         * Only the environment variables listed on [CommerceServiceConfiguration] are read,
         * so unrelated variables can never change the configuration.
         */
        fun load(
            environment: Map<String, String> = System.getenv(),
            resource: String = "/application.conf",
        ): CommerceServiceConfiguration =
            ConfigLoaderBuilder
                .empty()
                .addDefaultDecoders()
                .addDefaultParsers()
                .addDefaultParamMappers()
                .addDefaultNodeTransformers()
                .addDefaultResolvers()
                .addSource(PropertySource.resource(resource))
                .build()
                .loadConfigOrThrow<CommerceServiceConfiguration>()
                .withEnvironmentOverrides(Environment(environment))
                .also { it.validate() }
    }
}

private fun CommerceServiceConfiguration.withEnvironmentOverrides(environment: Environment) =
    copy(
        server = server.copy(port = environment.int("PORT", server.port)),
        database =
            database.copy(
                jdbcUrl = environment.string("DATABASE_JDBC_URL", database.jdbcUrl),
                username = environment.string("DATABASE_USERNAME", database.username),
                password = environment.string("DATABASE_PASSWORD", database.password),
                maximumPoolSize = environment.int("DATABASE_MAXIMUM_POOL_SIZE", database.maximumPoolSize),
                minimumIdle = environment.int("DATABASE_MINIMUM_IDLE", database.minimumIdle),
                connectionTimeoutMs = environment.long("DATABASE_CONNECTION_TIMEOUT_MS", database.connectionTimeoutMs),
                validationTimeoutMs = environment.long("DATABASE_VALIDATION_TIMEOUT_MS", database.validationTimeoutMs),
            ),
        flyway = flyway.copy(enabled = environment.boolean("FLYWAY_ENABLED", flyway.enabled)),
    )

private class Environment(
    private val values: Map<String, String>,
) {
    fun string(
        name: String,
        fallback: String,
    ): String = values[name] ?: fallback

    fun int(
        name: String,
        fallback: Int,
    ): Int = values[name]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer") } ?: fallback

    fun long(
        name: String,
        fallback: Long,
    ): Long = values[name]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer") } ?: fallback

    fun boolean(
        name: String,
        fallback: Boolean,
    ): Boolean =
        values[name]?.let { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$name must be true or false") }
            ?: fallback
}
