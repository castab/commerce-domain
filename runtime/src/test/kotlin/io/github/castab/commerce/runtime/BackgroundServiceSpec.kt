package io.github.castab.commerce.runtime

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.commerce.runtime.outbox.OutboxDispatcher
import io.github.castab.commerce.runtime.outbox.OutboxTransport
import io.github.castab.commerce.runtime.outbox.PublicationResult
import io.github.castab.commerce.runtime.outbox.awaitOutbox
import io.github.castab.commerce.runtime.outbox.fastSettings
import io.github.castab.commerce.runtime.outbox.testEvent
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.github.castab.commerce.runtime.testing.withTestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BackgroundServiceSpec :
    FunSpec({
        test("factories compose without starting, services start once and close in reverse order before the pool") {
            withTestDatabase { database, _ ->
                val actions = mutableListOf<String>()
                lateinit var context: CommerceRuntimeContext
                val runtime =
                    commerceRuntime(
                        database.backgroundConfiguration(),
                        ApplicationContributions(
                            backgroundServices =
                                listOf(
                                    { ctx ->
                                        context = ctx
                                        testService("one", actions, ctx)
                                    },
                                    { ctx -> testService("two", actions, ctx) },
                                ),
                        ),
                    )
                actions shouldBe emptyList()
                runtime.start()
                runtime.start()
                runtime.close()
                runtime.close()
                actions shouldBe listOf("start-one", "start-two", "close-two", "close-one")
                shouldThrow<Exception> { context.transactor.inTransaction { it.handle.execute("SELECT 1") } }
                shouldThrow<IllegalStateException> { runtime.start() }
            }
        }

        test("partial start failure closes every constructed service including failing and unstarted ones and closes the pool") {
            withTestDatabase { database, _ ->
                val actions = mutableListOf<String>()
                lateinit var context: CommerceRuntimeContext
                val runtime =
                    commerceRuntime(
                        database.backgroundConfiguration(),
                        ApplicationContributions(
                            backgroundServices =
                                listOf(
                                    { ctx ->
                                        context = ctx
                                        testService("one", actions, ctx)
                                    },
                                    { ctx -> testService("two", actions, ctx, failStart = true) },
                                    { ctx -> testService("three", actions, ctx) },
                                ),
                        ),
                    )
                shouldThrow<IllegalStateException> { runtime.start() }.message shouldBe "start-two"
                actions shouldBe listOf("start-one", "start-two", "close-three", "close-two", "close-one")
                shouldThrow<Exception> { context.transactor.inTransaction { it.handle.execute("SELECT 1") } }
            }
        }

        test("factory failure cleans previously constructed services and pool; cleanup errors do not hide the failure") {
            withTestDatabase { database, _ ->
                val actions = mutableListOf<String>()
                lateinit var context: CommerceRuntimeContext
                val failure =
                    shouldThrow<IllegalStateException> {
                        commerceRuntime(
                            database.backgroundConfiguration(),
                            ApplicationContributions(
                                backgroundServices =
                                    listOf(
                                        { ctx ->
                                            context = ctx
                                            testService("one", actions, ctx, failClose = true)
                                        },
                                        { error("factory failed") },
                                    ),
                            ),
                        )
                    }
                failure.message shouldBe "factory failed"
                failure.suppressed.single().message shouldBe "close-one"
                actions shouldBe listOf("close-one")
                shouldThrow<Exception> { context.transactor.inTransaction { it.handle.execute("SELECT 1") } }
            }
        }

        test("close failure still releases the other services and pool") {
            withTestDatabase { database, _ ->
                val actions = mutableListOf<String>()
                lateinit var context: CommerceRuntimeContext
                val runtime =
                    commerceRuntime(
                        database.backgroundConfiguration(),
                        ApplicationContributions(
                            backgroundServices =
                                listOf(
                                    { ctx ->
                                        context = ctx
                                        testService("one", actions, ctx)
                                    },
                                    { ctx -> testService("two", actions, ctx, failClose = true) },
                                ),
                        ),
                    )
                runtime.start()
                shouldThrow<IllegalStateException> { runtime.close() }.message shouldBe "close-two"
                actions shouldBe listOf("start-one", "start-two", "close-two", "close-one")
                shouldThrow<Exception> { context.transactor.inTransaction { it.handle.execute("SELECT 1") } }
            }
        }

        test("a consuming runtime commits business data, stops before delivery, and a new runtime publishes its event") {
            withTestDatabase { database, _ ->
                lateinit var context: CommerceRuntimeContext
                val event = testEvent()
                val id = UUID.randomUUID()
                commerceRuntime(
                    database.backgroundConfiguration(),
                    ApplicationContributions(
                        migrations = testApplicationMigrations(),
                        routes = { ctx ->
                            context = ctx
                            emptyList()
                        },
                    ),
                ).use {
                    it.start()
                    context.transactor.inTransaction { tx ->
                        tx.handle
                            .createUpdate(
                                "INSERT INTO testapp.test_application_records VALUES (:id, 'committed')",
                            ).bind("id", id)
                            .execute()
                        context.outbox.append(tx, event)
                    }
                }
                commerceRuntime(
                    database.backgroundConfiguration(),
                    ApplicationContributions(
                        migrations = testApplicationMigrations(),
                        backgroundServices =
                            listOf({ ctx ->
                                context = ctx
                                OutboxDispatcher(
                                    ctx.outbox.publisher("example", "restarted"),
                                    OutboxTransport {
                                        it.event shouldBe event
                                        ctx.transactor.inTransaction { tx ->
                                            tx.handle
                                                .createQuery("SELECT id FROM testapp.test_application_records")
                                                .mapTo(UUID::class.java)
                                                .one() shouldBe
                                                id
                                        }
                                        PublicationResult.CONFIRMED
                                    },
                                    fastSettings(),
                                )
                            }),
                    ),
                ).use {
                    it.start()
                    awaitOutbox { context.outbox.backlog("example").unpublished == 0L }
                }
            }
        }
    })

private fun TestDatabase.backgroundConfiguration(): CommerceRuntimeConfiguration =
    CommerceRuntimeConfiguration(
        server = CommerceRuntimeConfiguration.Server(0),
        database = configuration,
        migrations = CommerceRuntimeConfiguration.Migrations(OnStartup.MIGRATE),
    )

private fun testService(
    name: String,
    actions: MutableList<String>,
    context: CommerceRuntimeContext,
    failStart: Boolean = false,
    failClose: Boolean = false,
): ManagedBackgroundService =
    object : ManagedBackgroundService {
        override fun start() {
            actions.add("start-$name")
            if (failStart) error("start-$name")
        }

        override fun close() {
            // Proves that the pool remains usable until the very last service closes.
            context.transactor.inTransaction { it.handle.execute("SELECT 1") }
            actions.add("close-$name")
            if (failClose) error("close-$name")
        }
    }
