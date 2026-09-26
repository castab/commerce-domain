package io.github.castab.commerce.service.persistence

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Applies the Flyway migrations of commerce-service, then those of the application.
 *
 * The two sets evolve independently, so each has its own history table:
 *
 * - commerce-service migrations are discovered at [COMMERCE_LOCATION] and own the
 *   [COMMERCE_SCHEMA] schema, tracked in `commerce.flyway_schema_history`;
 * - application migrations are discovered at [applicationLocations] (for example
 *   `classpath:db/migration`), own [applicationSchema], and are tracked in
 *   `<applicationSchema>.flyway_schema_history`.
 *
 * Both schemas are always explicit. PostgreSQL's default search path starts with
 * `"$user"`, so relying on the connection's current schema would silently select the
 * `commerce` schema whenever the database role is itself named `commerce`.
 *
 * Commerce migrations always run first, so application migrations may reference commerce
 * tables. Neither set may change the other's tables.
 */
class DatabaseMigrations(
    private val dataSource: DataSource,
    private val applicationLocations: List<String> = emptyList(),
    private val applicationSchema: String = "public",
) {
    fun migrate() {
        Flyway
            .configure()
            .dataSource(dataSource)
            .schemas(COMMERCE_SCHEMA)
            .createSchemas(true)
            .locations(COMMERCE_LOCATION)
            .failOnMissingLocations(true)
            .load()
            .migrate()

        if (applicationLocations.isNotEmpty()) {
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas(applicationSchema)
                .locations(*applicationLocations.toTypedArray())
                .failOnMissingLocations(true)
                .load()
                .migrate()
        }
    }

    companion object {
        /** The PostgreSQL schema that holds every commerce-service table. */
        const val COMMERCE_SCHEMA = "commerce"

        /** Where commerce-service's own migrations are discovered. Applications must not use it. */
        const val COMMERCE_LOCATION = "classpath:db/commerce"
    }
}
