package io.github.castab.commerce.runtime.outbox

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.testing.FinancialLedgerFixture
import io.github.castab.commerce.runtime.testing.appliedVersions
import io.github.castab.commerce.runtime.testing.depositMoney
import io.github.castab.commerce.runtime.testing.execute
import io.github.castab.commerce.runtime.testing.strings
import io.github.castab.commerce.runtime.testing.withTestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OutboxSpec :
    FunSpec({
        test("a stale repeatable-read append conflicts and leaves committed ordering intact") {
            withOutbox { fixture, outbox ->
                fixture.transactor.inTransaction { outbox.append(it, testEvent(aggregate = "same")) }
                shouldThrow<CommerceFailure.Conflict> {
                    fixture.transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { tx ->
                        tx.handle
                            .createQuery("SELECT last_sequence FROM commerce.outbox_aggregates")
                            .mapTo(Long::class.java)
                            .one() shouldBe 1L
                        fixture.onOtherThread {
                            fixture.transactor.inTransaction { outbox.append(it, testEvent(aggregate = "same")) }
                        }
                        outbox.append(tx, testEvent(aggregate = "same"))
                    }
                }
                val next = fixture.transactor.inTransaction { outbox.append(it, testEvent(aggregate = "same")) }
                next.aggregateSequence shouldBe 3L
                outbox.backlog("example").unpublished shouldBe 3L
            }
        }

        test("financial facts, application rows, and events share the authoritative business transaction") {
            withOutbox { fixture, outbox ->
                val document =
                    FinancialDocument.Invoice.create(
                        UUID.randomUUID(),
                        listOf(LineItem(UUID.randomUUID(), "Service", null, null, depositMoney("10"), depositMoney("0"))),
                    )
                val event = testEvent()

                fun operation(rollback: Boolean) =
                    fixture.transactor.inTransaction { tx ->
                        tx.handle
                            .createUpdate("INSERT INTO testapp.test_application_records VALUES (:id, 'invoice')")
                            .bind("id", document.id)
                            .execute()
                        fixture.ledger.create(tx, document)
                        outbox.append(tx, event)
                        if (rollback) error("abort entire operation")
                    }
                shouldThrow<IllegalStateException> { operation(true) }
                fixture.dataSource.strings("SELECT count(*) FROM commerce.financial_document_snapshots").single() shouldBe "0"
                fixture.dataSource.strings("SELECT count(*) FROM testapp.test_application_records").single() shouldBe "0"
                outbox.backlog("example").unpublished shouldBe 0L
                operation(false)
                fixture.dataSource.strings("SELECT count(*) FROM commerce.financial_document_snapshots").single() shouldBe "1"
                fixture.dataSource.strings("SELECT count(*) FROM testapp.test_application_records").single() shouldBe "1"
                outbox.backlog("example").unpublished shouldBe 1L
            }
        }

        test("events and application commands commit or roll back together, with application idempotency") {
            withTestDatabase { _, pool ->
                MigrationLifecycle(pool).migrate()
                pool.execute("CREATE SCHEMA example; CREATE TABLE example.commands (id uuid PRIMARY KEY)")
                val transactor = Transactor(Jdbi.create(pool))
                val outbox = Outbox(transactor)
                val command = UUID.randomUUID()
                val event = testEvent()

                fun operation(fail: Boolean) =
                    transactor.inTransaction { tx ->
                        val inserted =
                            tx.handle
                                .createUpdate("INSERT INTO example.commands VALUES (:id) ON CONFLICT DO NOTHING")
                                .bind("id", command)
                                .execute()
                        if (inserted == 0) return@inTransaction
                        outbox.append(tx, event).aggregateSequence shouldBe 1L
                        outbox.append(tx, testEvent(aggregate = event.aggregateId)).aggregateSequence shouldBe 2L
                        if (fail) error("abort business operation")
                    }
                shouldThrow<IllegalStateException> { operation(true) }
                pool.strings("SELECT count(*) FROM example.commands").single() shouldBe "0"
                outbox.backlog("example").unpublished shouldBe 0L
                operation(false)
                operation(false)
                pool.strings("SELECT count(*) FROM example.commands").single() shouldBe "1"
                outbox.backlog("example").unpublished shouldBe 2L
                pool.strings("SELECT aggregate_sequence FROM commerce.outbox_events ORDER BY aggregate_sequence") shouldContainExactly
                    listOf("1", "2")
            }
        }

        test("duplicate identity conflicts instead of overwriting immutable content or consuming a sequence") {
            withOutbox { fixture, outbox ->
                val event = testEvent(payload = "{\n  \"exact\": 1.00, \"unicode\": \"☕\"\n}\n")
                fixture.transactor.inTransaction { outbox.append(it, event) }
                listOf(event, event.copy(payload = "conflicting")).forEach { duplicate ->
                    shouldThrow<CommerceFailure.Conflict> {
                        fixture.transactor.inTransaction { outbox.append(it, duplicate) }
                    }
                }
                val successor = fixture.transactor.inTransaction { outbox.append(it, testEvent(aggregate = event.aggregateId)) }
                successor.aggregateSequence shouldBe 2L
                val publisher = outbox.publisher("example", "one")
                val claim = publisher.claim(1, Duration.ofMinutes(1)).single()
                claim.record.event shouldBe event
                claim.record.aggregateSequence shouldBe 1L
                shouldThrow<Exception> {
                    fixture.transactor.inTransaction { it.handle.execute("UPDATE commerce.outbox_events SET payload = 'changed'") }
                }
                publisher.confirm(claim) shouldBe true
                publisher.confirm(claim) shouldBe false
                shouldThrow<Exception> {
                    fixture.transactor.inTransaction { it.handle.execute("UPDATE commerce.outbox_events SET published_at = NULL") }
                }
            }
        }

        test("pending, leased, and retry-delayed predecessors block only their own aggregate and producer") {
            withOutbox { fixture, outbox ->
                val first = testEvent(aggregate = "a")
                fixture.transactor.inTransaction { tx ->
                    outbox.append(tx, first)
                    outbox.append(tx, testEvent(aggregate = "a"))
                    outbox.append(tx, testEvent(aggregate = "b"))
                    outbox.append(tx, testEvent(producer = "other", aggregate = "a"))
                }
                val one = outbox.publisher("example", "one")
                val claims = one.claim(100, Duration.ofMinutes(1))
                claims.size shouldBe 2
                outbox.backlog("example").claimed shouldBe 2L
                outbox.backlog("other").retryEligible shouldBe 1L
                one.claim(10, Duration.ofMinutes(1)).shouldBeEmpty()
                val a = claims.single { it.record.event.id == first.id }
                one.fail(a, OutboxFailureCode.UNCONFIRMED, Duration.ofHours(1)) shouldBe true
                outbox.backlog("example").waitingForRetry shouldBe 1L
                outbox.backlog("example").failed shouldBe 1L
                one.claim(10, Duration.ofMinutes(1)).shouldBeEmpty()
                fixture.transactor.inTransaction { it.handle.execute("UPDATE commerce.outbox_events SET next_attempt_at = '-infinity'") }
                val retried = one.claim(10, Duration.ofMinutes(1)).single()
                retried.record.event shouldBe first
                retried.attempts shouldBe 2L
                one.confirm(retried) shouldBe true
                one
                    .claim(10, Duration.ofMinutes(1))
                    .single()
                    .record.aggregateSequence shouldBe 2L
            }
        }

        test("expiry is recoverable at the boundary and tokens fence stale success and failure") {
            withOutbox { fixture, outbox ->
                fixture.transactor.inTransaction { outbox.append(it, testEvent()) }
                val oldPublisher = outbox.publisher("example", "old")
                val old = oldPublisher.claim(1, Duration.ofMinutes(1)).single()
                outbox.publisher("other", "other").confirm(old) shouldBe false
                outbox.publisher("other", "other").fail(old, OutboxFailureCode.TIMEOUT, Duration.ofMillis(1)) shouldBe false
                val newPublisher = outbox.publisher("example", "new")
                newPublisher.claim(1, Duration.ofMinutes(1)).shouldBeEmpty()
                fixture.transactor.inTransaction { tx ->
                    tx.handle.execute("UPDATE commerce.outbox_events SET claimed_until = statement_timestamp()")
                }
                // Time is moved in SQL instead of sleeping for a lease. Expired tokens cannot finalize even before reclamation.
                oldPublisher.confirm(old) shouldBe false
                val fresh = newPublisher.claim(1, Duration.ofMinutes(1)).single()
                (fresh.token == old.token) shouldBe false
                fresh.attempts shouldBe 2L
                fresh.record.event shouldBe old.record.event
                oldPublisher.confirm(old) shouldBe false
                oldPublisher.fail(old, OutboxFailureCode.TIMEOUT, Duration.ofMillis(1)) shouldBe false
                newPublisher.confirm(fresh) shouldBe true
                outbox.backlog("example").unpublished shouldBe 0L
            }
        }

        test("concurrent publishers claim distinct aggregate heads with no duplicate live claims") {
            withOutbox { fixture, outbox ->
                fixture.transactor.inTransaction { tx -> repeat(20) { outbox.append(tx, testEvent(aggregate = "aggregate-$it")) } }
                val barrier = CyclicBarrier(2)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val futures =
                        (1..2).map { number ->
                            executor.submit(
                                Callable {
                                    barrier.await(10, TimeUnit.SECONDS)
                                    outbox.publisher("example", "publisher-$number").claim(10, Duration.ofMinutes(1))
                                },
                            )
                        }
                    val all = futures.flatMap { it.get(10, TimeUnit.SECONDS) }
                    all.size shouldBe 20
                    all.map { it.record.event.id }.toSet().size shouldBe 20
                    all.map { it.token }.toSet().size shouldBe 20
                    outbox.backlog("example").claimed shouldBe 20L
                } finally {
                    executor.shutdownNow()
                }
            }
        }

        test("an append waits for the same aggregate transaction to commit and cannot overtake uncommitted content") {
            listOf(false, true).forEach { rollback ->
                withOutbox { fixture, outbox ->
                    val executor = Executors.newFixedThreadPool(2)
                    val release = CountDownLatch(1)
                    val firstPid = CompletableFuture<Int>()
                    val secondPid = CompletableFuture<Int>()
                    try {
                        val holder =
                            executor.submit(
                                Callable {
                                    runCatching {
                                        fixture.transactor.inTransaction { tx ->
                                            outbox.append(tx, testEvent(aggregate = "same"))
                                            firstPid.complete(fixture.pid(tx))
                                            check(release.await(20, TimeUnit.SECONDS))
                                            if (rollback) error("rollback first")
                                        }
                                    }
                                },
                            )
                        firstPid.get(10, TimeUnit.SECONDS)
                        val waiter =
                            executor.submit(
                                Callable {
                                    fixture.transactor.inTransaction { tx ->
                                        secondPid.complete(fixture.pid(tx))
                                        outbox.append(tx, testEvent(aggregate = "same"))
                                    }
                                },
                            )
                        fixture.awaitBlocked(secondPid.get(10, TimeUnit.SECONDS), firstPid.get())
                        val publisher = outbox.publisher("example", "one")
                        publisher.claim(10, Duration.ofMinutes(1)).shouldBeEmpty()
                        // An unrelated aggregate neither waits on the counter nor on publication.
                        val independent = fixture.transactor.inTransaction { outbox.append(it, testEvent(aggregate = "independent")) }
                        independent.aggregateSequence shouldBe 1L
                        publisher
                            .claim(10, Duration.ofMinutes(1))
                            .single()
                            .record.event.aggregateId shouldBe "independent"
                        release.countDown()
                        holder.get(10, TimeUnit.SECONDS).isFailure shouldBe rollback
                        waiter.get(10, TimeUnit.SECONDS).aggregateSequence shouldBe if (rollback) 1L else 2L
                        publisher
                            .claim(10, Duration.ofMinutes(1))
                            .single()
                            .record.aggregateSequence shouldBe 1L
                    } finally {
                        release.countDown()
                        executor.shutdownNow()
                    }
                }
            }
        }

        test("claim and finalization reject an open application transaction") {
            withOutbox { fixture, outbox ->
                fixture.transactor.inTransaction { outbox.append(it, testEvent()) }
                val publisher = outbox.publisher("example", "one")
                shouldThrow<IllegalStateException> {
                    fixture.transactor.inTransaction { publisher.claim(1, Duration.ofMinutes(1)) }
                }
                val claim = publisher.claim(1, Duration.ofMinutes(1)).single()
                shouldThrow<IllegalStateException> { fixture.transactor.inTransaction { publisher.confirm(claim) } }
                publisher.confirm(claim) shouldBe true
            }
        }

        test("fresh and populated V1 databases migrate additively and validate without application schema changes") {
            withTestDatabase { _, pool ->
                Flyway
                    .configure()
                    .dataSource(pool)
                    .defaultSchema("commerce")
                    .schemas("commerce")
                    .locations("classpath:db/commerce")
                    .target("1")
                    .load()
                    .migrate()
                val id = UUID.randomUUID()
                pool.execute("INSERT INTO commerce.principals VALUES ('USER', '$id', 'ACTIVE')")
                MigrationLifecycle(pool).migrate()
                MigrationLifecycle(pool).validate()
                pool.appliedVersions("commerce") shouldContainExactly listOf("1", "2")
                pool.strings("SELECT principal_id FROM commerce.principals").single() shouldBe id.toString()
                pool.strings("SELECT DISTINCT table_schema FROM information_schema.tables WHERE table_name LIKE 'outbox_%'") shouldBe
                    listOf("commerce")
            }
        }

        test("invalid envelopes and timing policy fail before persistence or startup") {
            val event = testEvent()
            shouldThrow<IllegalArgumentException> { event.copy(schemaVersion = 0) }
            shouldThrow<IllegalArgumentException> { event.copy(payload = "secret\u0000data") }
            shouldThrow<IllegalArgumentException> { event.copy(payload = "\uD800") }
            shouldThrow<IllegalArgumentException> { event.copy(producerId = " other ") }
            shouldThrow<IllegalArgumentException> { event.copy(occurredAt = Instant.EPOCH.plusNanos(1)) }
            shouldThrow<IllegalArgumentException> { OutboxDispatchSettings(batchSize = 0) }
            shouldThrow<IllegalArgumentException> { OutboxDispatchSettings(lease = Duration.ofSeconds(1)) }
            val settings = OutboxDispatchSettings()
            settings.retryDelay(1) shouldBe Duration.ofSeconds(1)
            settings.retryDelay(2) shouldBe Duration.ofSeconds(2)
            settings.retryDelay(Long.MAX_VALUE) shouldBe Duration.ofMinutes(5)
            event.toString() shouldBe "OutboxEvent(id=${event.id})"
        }
    })

internal fun testEvent(
    producer: String = "example",
    aggregate: String = UUID.randomUUID().toString(),
    payload: String = "{ \"application\": \"opaque\" }\n",
): OutboxEvent =
    OutboxEvent(
        UUID.randomUUID(),
        producer,
        "record",
        aggregate,
        "RecordSaved",
        1,
        "events.records",
        payload,
        Instant.EPOCH,
    )

internal fun withOutbox(block: (FinancialLedgerFixture, Outbox) -> Unit) {
    FinancialLedgerFixture().use { fixture -> block(fixture, Outbox(fixture.transactor)) }
}
