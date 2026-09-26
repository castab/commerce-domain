package io.github.castab.commerce.service.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.service.config.CommerceServiceConfiguration
import java.sql.SQLException
import javax.sql.DataSource

/** Creates the service's single HikariCP connection pool. The caller owns and closes it. */
fun createDataSource(
    configuration: CommerceServiceConfiguration.Database,
    poolName: String = "commerce-service",
): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = configuration.jdbcUrl
            username = configuration.username
            password = configuration.password
            maximumPoolSize = configuration.maximumPoolSize
            minimumIdle = configuration.minimumIdle
            connectionTimeout = configuration.connectionTimeoutMs
            validationTimeout = configuration.validationTimeoutMs
            this.poolName = poolName
        },
    )

/** True when a pooled connection can be obtained and validated. Used by readiness checks. */
fun DataSource.isReachable(timeoutSeconds: Int = 1): Boolean =
    try {
        connection.use { it.isValid(timeoutSeconds) }
    } catch (_: SQLException) {
        false
    }

private const val UNIQUE_VIOLATION = "23505"

/**
 * True when this failure, or one of its causes, is a PostgreSQL unique-constraint
 * violation. Repositories translate it into an application conflict instead of letting
 * database detail reach callers.
 */
fun Throwable.isUniqueViolation(): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == UNIQUE_VIOLATION }
