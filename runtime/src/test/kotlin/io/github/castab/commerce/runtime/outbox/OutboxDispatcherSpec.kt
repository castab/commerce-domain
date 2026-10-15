package io.github.castab.commerce.runtime.outbox

import io.github.castab.commerce.runtime.testing.capturingStandardOutput
import io.github.castab.commerce.runtime.testing.execute
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OutboxDispatcherSpec :
    FunSpec({
        test("confirmed transport acceptance followed by failed database finalization retains the lease for recovery") {
            withOutbox { fixture, outbox ->
                val event = testEvent()
                fixture.transactor.inTransaction { outbox.append(it, event) }
                fixture.dataSource.execute(
                    """CREATE SEQUENCE testapp.confirmation_attempts;
                   CREATE FUNCTION testapp.reject_confirmation() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                   BEGIN IF NEW.published_at IS NOT NULL THEN
                     PERFORM nextval('testapp.confirmation_attempts');
                     RAISE EXCEPTION 'database unavailable for confirmation'; END IF;
                   RETURN NEW; END; ${'$'}${'$'};
                   CREATE TRIGGER reject_confirmation BEFORE UPDATE ON commerce.outbox_events
                     FOR EACH ROW EXECUTE FUNCTION testapp.reject_confirmation();""",
                )
                val delivered = CopyOnWriteArrayList<OutboxEvent>()
                val accepted = CountDownLatch(1)
                OutboxDispatcher(
                    outbox.publisher("example", "old"),
                    OutboxTransport {
                        delivered.add(it.event)
                        accepted.countDown()
                        PublicationResult.CONFIRMED
                    },
                    fastSettings(),
                ).use {
                    it.start()
                    check(accepted.await(10, TimeUnit.SECONDS))
                    // Wait until the failed confirmation transaction has actually reached PostgreSQL.
                    awaitOutbox {
                        fixture.transactor.inTransaction { tx ->
                            tx.handle
                                .createQuery(
                                    "SELECT is_called FROM testapp.confirmation_attempts",
                                ).mapTo(Boolean::class.java)
                                .one()
                        }
                    }
                }
                outbox.backlog("example").unpublished shouldBe 1L
                outbox.backlog("example").claimed shouldBe 1L
                fixture.dataSource.execute("DROP TRIGGER reject_confirmation ON commerce.outbox_events")
                fixture.transactor.inTransaction {
                    it.handle.execute(
                        "UPDATE commerce.outbox_events SET claimed_until = statement_timestamp()",
                    )
                }
                OutboxDispatcher(
                    outbox.publisher("example", "new"),
                    OutboxTransport {
                        delivered.add(it.event)
                        PublicationResult.CONFIRMED
                    },
                    fastSettings(),
                ).use {
                    it.start()
                    awaitOutbox { outbox.backlog("example").unpublished == 0L }
                }
                delivered shouldBe listOf(event, event)
            }
        }

        test("confirmed acceptance finalizes a committed claim outside database locks") {
            withOutbox { fixture, outbox ->
                val event = testEvent()
                fixture.transactor.inTransaction { outbox.append(it, event) }
                val accepted = CountDownLatch(1)
                val dispatcher =
                    OutboxDispatcher(
                        outbox.publisher("example", "one"),
                        OutboxTransport { record ->
                            record.event shouldBe event
                            outbox.backlog("example").claimed shouldBe 1L
                            // A different connection can lock the claimed row: no publication-time lock survives.
                            fixture.transactor.inTransaction { tx ->
                                tx.handle
                                    .createQuery("SELECT id FROM commerce.outbox_events FOR UPDATE NOWAIT")
                                    .mapTo(java.util.UUID::class.java)
                                    .one() shouldBe event.id
                            }
                            accepted.countDown()
                            PublicationResult.CONFIRMED
                        },
                        fastSettings(),
                    )
                dispatcher.use {
                    it.start()
                    check(accepted.await(10, TimeUnit.SECONDS))
                    awaitOutbox { outbox.backlog("example").unpublished == 0L }
                }
                fixture.dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT attempts, published_at IS NOT NULL FROM commerce.outbox_events").use { rows ->
                            rows.next() shouldBe true
                            rows.getLong(1) shouldBe 1L
                            rows.getBoolean(2) shouldBe true
                        }
                    }
                }
            }
        }

        test("lost acknowledgment and restart redeliver the identical event and aggregate sequence") {
            withOutbox { fixture, outbox ->
                val original = testEvent(payload = "{  \"value\": 1.00, \"contact\": \"PRIVATE\" }\n")
                fixture.transactor.inTransaction { outbox.append(it, original) }
                val delivered = CopyOnWriteArrayList<RecordedOutboxEvent>()
                val first =
                    OutboxDispatcher(
                        outbox.publisher("example", "old"),
                        OutboxTransport { event ->
                            delivered.add(event) // Externally accepted; response was lost.
                            PublicationResult.UNCONFIRMED
                        },
                        fastSettings().copy(retryInitial = Duration.ofHours(1), retryMaximum = Duration.ofHours(1)),
                    )
                first.use {
                    it.start()
                    awaitOutbox { outbox.backlog("example").failed == 1L }
                }
                outbox.backlog("example").unpublished shouldBe 1L
                fixture.transactor.inTransaction {
                    it.handle.execute("UPDATE commerce.outbox_events SET next_attempt_at = statement_timestamp()")
                }
                OutboxDispatcher(
                    outbox.publisher("example", "new"),
                    OutboxTransport {
                        delivered.add(it)
                        PublicationResult.CONFIRMED
                    },
                    fastSettings(),
                ).use {
                    it.start()
                    awaitOutbox { outbox.backlog("example").unpublished == 0L }
                }
                delivered.size shouldBe 2
                delivered.forEach {
                    it.event shouldBe original
                    it.aggregateSequence shouldBe 1L
                    it.recordedAt shouldBe delivered.first().recordedAt
                }
            }
        }

        test("external success before crash and database acknowledgment loss is recovered with stable identity") {
            withOutbox { fixture, outbox ->
                val event = testEvent()
                fixture.transactor.inTransaction { outbox.append(it, event) }
                val old = outbox.publisher("example", "terminated").claim(1, Duration.ofMinutes(1)).single()
                val externalDeliveries = mutableListOf(old.record.event) // Accepted externally; process died before confirm.
                fixture.transactor.inTransaction {
                    it.handle.execute("UPDATE commerce.outbox_events SET claimed_until = statement_timestamp()")
                }
                OutboxDispatcher(
                    outbox.publisher("example", "restarted"),
                    OutboxTransport {
                        synchronized(externalDeliveries) { externalDeliveries.add(it.event) }
                        PublicationResult.CONFIRMED
                    },
                    fastSettings(),
                ).use {
                    it.start()
                    awaitOutbox { outbox.backlog("example").unpublished == 0L }
                }
                externalDeliveries shouldBe listOf(event, event)
            }
        }

        test("transport exceptions retain events indefinitely and logs exclude payload and exception secrets") {
            withOutbox { fixture, outbox ->
                fixture.transactor.inTransaction { outbox.append(it, testEvent(payload = "PRIVATE_PAYLOAD")) }
                val failures = CountDownLatch(4)
                val output =
                    capturingStandardOutput {
                        OutboxDispatcher(
                            outbox.publisher("example", "one"),
                            OutboxTransport {
                                failures.countDown()
                                error("TOKEN_SECRET and PRIVATE_PAYLOAD")
                            },
                            fastSettings().copy(retryInitial = Duration.ofMillis(1), retryMaximum = Duration.ofMillis(2)),
                        ).use {
                            it.start()
                            check(failures.await(10, TimeUnit.SECONDS))
                            awaitOutbox { outbox.backlog("example").failed == 1L }
                        }
                    }
                output shouldNotContain "PRIVATE_PAYLOAD"
                output shouldNotContain "TOKEN_SECRET"
                outbox.backlog("example").unpublished shouldBe 1L
            }
        }

        test("a hung interrupt-ignoring transport is bounded and cannot prevent shutdown or lease recovery") {
            withOutbox { fixture, outbox ->
                fixture.transactor.inTransaction { outbox.append(it, testEvent()) }
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val settings = fastSettings().copy(publishTimeout = Duration.ofMillis(40))
                val dispatcher =
                    OutboxDispatcher(
                        outbox.publisher("example", "one"),
                        OutboxTransport {
                            entered.countDown()
                            while (release.count > 0) {
                                try {
                                    release.await()
                                } catch (_: InterruptedException) {
                                    // Deliberately broken adapter. The runtime cannot forcibly stop external I/O.
                                }
                            }
                            PublicationResult.CONFIRMED
                        },
                        settings,
                    )
                try {
                    dispatcher.start()
                    check(entered.await(10, TimeUnit.SECONDS))
                    awaitOutbox { outbox.backlog("example").failed == 1L }
                    val start = System.nanoTime()
                    dispatcher.close()
                    (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3)) shouldBe true
                    outbox.backlog("example").unpublished shouldBe 1L
                } finally {
                    release.countDown()
                    dispatcher.close()
                }
            }
        }

        test("an independent aggregate progresses while an earlier aggregate is delayed") {
            withOutbox { fixture, outbox ->
                fixture.transactor.inTransaction { tx ->
                    outbox.append(tx, testEvent(aggregate = "broken"))
                    outbox.append(tx, testEvent(aggregate = "broken"))
                    outbox.append(tx, testEvent(aggregate = "healthy"))
                }
                val healthy = CountDownLatch(1)
                OutboxDispatcher(
                    outbox.publisher("example", "one"),
                    OutboxTransport {
                        if (it.event.aggregateId == "broken") {
                            PublicationResult.UNCONFIRMED
                        } else {
                            healthy.countDown()
                            PublicationResult.CONFIRMED
                        }
                    },
                    fastSettings().copy(batchSize = 2),
                ).use {
                    it.start()
                    check(healthy.await(10, TimeUnit.SECONDS))
                    awaitOutbox { outbox.backlog("example").unpublished == 2L }
                }
            }
        }
    })

internal fun fastSettings(): OutboxDispatchSettings =
    OutboxDispatchSettings(
        batchSize = 1,
        pollInterval = Duration.ofMillis(10),
        lease = Duration.ofMinutes(1),
        publishTimeout = Duration.ofSeconds(1),
        retryInitial = Duration.ofMillis(10),
        retryMaximum = Duration.ofMillis(100),
    )

internal fun awaitOutbox(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Outbox did not reach the expected durable state" }
        Thread.sleep(10)
    }
}
