package io.github.castab.commerce.service.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class CommerceServiceConfigurationSpec :
    FunSpec({
        val database =
            mapOf(
                "DATABASE_JDBC_URL" to "jdbc:postgresql://localhost:5432/commerce",
                "DATABASE_USERNAME" to "commerce",
                "DATABASE_PASSWORD" to "not-a-real-secret",
            )

        test("application.conf supplies the defaults and the environment supplies the database") {
            val configuration = CommerceServiceConfiguration.load(environment = database)

            configuration.server.port shouldBe 8080
            configuration.database.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/commerce"
            configuration.database.username shouldBe "commerce"
            configuration.database.password shouldBe "not-a-real-secret"
            configuration.database.maximumPoolSize shouldBe 4
            configuration.database.minimumIdle shouldBe 1
            configuration.flyway.enabled shouldBe false
        }

        test("every documented environment variable overrides its setting") {
            val configuration =
                CommerceServiceConfiguration.load(
                    environment =
                        database +
                            mapOf(
                                "PORT" to "9090",
                                "DATABASE_MAXIMUM_POOL_SIZE" to "10",
                                "DATABASE_MINIMUM_IDLE" to "2",
                                "DATABASE_CONNECTION_TIMEOUT_MS" to "750",
                                "DATABASE_VALIDATION_TIMEOUT_MS" to "1500",
                                "FLYWAY_ENABLED" to "true",
                            ),
                )

            configuration.server.port shouldBe 9090
            configuration.database.maximumPoolSize shouldBe 10
            configuration.database.minimumIdle shouldBe 2
            configuration.database.connectionTimeoutMs shouldBe 750
            configuration.database.validationTimeoutMs shouldBe 1500
            configuration.flyway.enabled shouldBe true
        }

        test("unrelated environment variables are ignored") {
            val configuration = CommerceServiceConfiguration.load(environment = database + ("SERVER_PORT" to "1"))

            configuration.server.port shouldBe 8080
        }

        test("a missing database is rejected, naming the variable") {
            shouldThrow<IllegalArgumentException> {
                CommerceServiceConfiguration.load(environment = emptyMap())
            }.message shouldBe "DATABASE_JDBC_URL is required"
        }

        test("malformed values are rejected, naming the variable") {
            shouldThrow<IllegalArgumentException> {
                CommerceServiceConfiguration.load(environment = database + ("PORT" to "eighty"))
            }.message shouldBe "PORT must be an integer"
            shouldThrow<IllegalArgumentException> {
                CommerceServiceConfiguration.load(environment = database + ("FLYWAY_ENABLED" to "yes"))
            }.message shouldBe "FLYWAY_ENABLED must be true or false"
            shouldThrow<IllegalArgumentException> {
                CommerceServiceConfiguration.load(environment = database + ("DATABASE_JDBC_URL" to "jdbc:h2:mem:test"))
            }.message shouldBe "DATABASE_JDBC_URL must be a PostgreSQL JDBC URL"
            shouldThrow<IllegalArgumentException> {
                CommerceServiceConfiguration.load(environment = database + ("DATABASE_MINIMUM_IDLE" to "5"))
            }.message shouldBe "DATABASE_MINIMUM_IDLE must be between zero and DATABASE_MAXIMUM_POOL_SIZE"
        }

        test("the database password never appears in the configuration's text") {
            val configuration = CommerceServiceConfiguration.load(environment = database)

            configuration.toString() shouldNotContain "not-a-real-secret"
            configuration.database.toString() shouldContain "password=****"
        }
    })
