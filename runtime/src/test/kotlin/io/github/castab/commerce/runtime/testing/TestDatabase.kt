package io.github.castab.commerce.runtime.testing

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import java.sql.DriverManager
import java.util.UUID

/**
 * A throwaway PostgreSQL database on the server Gradle provides to the test JVM (a Docker
 * container started by runtime/build.gradle.kts, or TEST_DATABASE_JDBC_URL).
 *
 * Each spec creates its own database, applies the real Flyway migrations through the code
 * under test, and drops the database when closed. There is no separate test schema.
 */
class TestDatabase private constructor(
    private val name: String,
    val configuration: CommerceRuntimeConfiguration.Database,
) : AutoCloseable {
    override fun close() {
        admin { it.createStatement().use { statement -> statement.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") } }
    }

    companion object {
        private fun property(name: String): String =
            System.getProperty("commerce.test.database.$name")
                ?: error("commerce.test.database.$name is not set; run the runtime tests through Gradle")

        private val serverUrl by lazy { property("jdbcUrl") }
        private val username by lazy { property("username") }
        private val password by lazy { property("password") }

        private fun <T> admin(block: (java.sql.Connection) -> T): T = DriverManager.getConnection(serverUrl, username, password).use(block)

        fun create(): TestDatabase {
            val name = "commerce_test_${UUID.randomUUID().toString().replace("-", "")}"
            admin { it.createStatement().use { statement -> statement.execute("CREATE DATABASE $name") } }
            val base = serverUrl.substringBefore('?')
            val query = serverUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
            val jdbcUrl = base.substringBeforeLast('/') + "/" + name + query
            return TestDatabase(
                name,
                CommerceRuntimeConfiguration.Database(
                    jdbcUrl = jdbcUrl,
                    username = username,
                    password = password,
                    maximumPoolSize = 4,
                    minimumIdle = 0,
                    connectionTimeoutMs = 5_000,
                ),
            )
        }
    }
}
