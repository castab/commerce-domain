package io.github.castab.commerce.runtime.outbox

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.testing.appliedVersions
import io.github.castab.commerce.runtime.testing.execute
import io.github.castab.commerce.runtime.testing.strings
import io.github.castab.commerce.runtime.testing.withTestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import java.time.Duration
import java.util.UUID

class OutboxJsonbSpec :
    FunSpec({
        test("JSONB preserves structured content and returns its stored publication representation") {
            withOutbox { fixture, outbox ->
                val event = testEvent(payload = """{ "z": [true, false, null, 2, {"nested":"☕🚀"}], "a": {"value":1.00} }""")
                val record = fixture.transactor.inTransaction { outbox.append(it, event) }
                fixture.dataSource
                    .strings(
                        "SELECT data_type FROM information_schema.columns WHERE table_schema = 'commerce' " +
                            "AND table_name = 'outbox_events' AND column_name = 'payload'",
                    ).single() shouldBe "jsonb"
                record.event.payload shouldBe event.payload
                record.payloadJson shouldBe fixture.dataSource.strings("SELECT payload::text FROM commerce.outbox_events").single()
                Json.parseToJsonElement(record.payloadJson).jsonObject shouldBe record.event.payload
                (record.payloadJson == event.payload.toString()) shouldBe false
                val publisher = outbox.publisher("example", "one")
                val claim = publisher.claim(1, Duration.ofMinutes(1)).single()
                claim.record.payloadJson shouldBe record.payloadJson
                publisher.fail(claim, OutboxFailureCode.UNCONFIRMED, Duration.ofMillis(1)) shouldBe true
                fixture.dataSource.execute("UPDATE commerce.outbox_events SET next_attempt_at = '-infinity'")
                val recovered = Outbox(fixture.transactor).publisher("example", "restarted")
                val retry = recovered.claim(1, Duration.ofMinutes(1)).single()
                retry.record.payloadJson
                    .toByteArray()
                    .toList() shouldBe record.payloadJson.toByteArray().toList()
                recovered.confirm(retry) shouldBe true
                fixture.dataSource.strings("SELECT payload::text FROM commerce.outbox_events").single() shouldBe record.payloadJson
            }
        }

        test("database rejects malformed JSON and non-object payloads independently of the typed API") {
            withOutbox { fixture, outbox ->
                val event = testEvent()
                fixture.transactor.inTransaction { outbox.append(it, event) }
                listOf("not json", "[]", "true", "42", "null", "\"string\"").forEach { payload ->
                    shouldThrow<Exception> {
                        fixture.transactor.inTransaction { tx ->
                            tx.handle
                                .createUpdate(
                                    """INSERT INTO commerce.outbox_events
                                (id, producer_id, aggregate_type, aggregate_id, aggregate_sequence,
                                 event_type, schema_version, destination, payload, occurred_at)
                                SELECT :id, producer_id, aggregate_type, aggregate_id, 2,
                                       event_type, schema_version, destination, CAST(:payload AS jsonb), occurred_at
                                FROM commerce.outbox_events WHERE id = :existing""",
                                ).bind("id", UUID.randomUUID())
                                .bind("payload", payload)
                                .bind("existing", event.id)
                                .execute()
                        }
                    }
                }
                outbox.backlog("example").unpublished shouldBe 1L
                shouldThrow<IllegalArgumentException> { testEvent(payload = "not json") }
                shouldThrow<IllegalArgumentException> { testEvent(payload = "[]") }
                shouldThrow<Exception> {
                    fixture.transactor.inTransaction { tx ->
                        outbox.append(tx, event.draft().copy(id = UUID.randomUUID())) {
                            buildJsonObject { put("value", JsonPrimitive(Double.NaN)) }
                        }
                    }
                }
                fixture.transactor
                    .inTransaction {
                        outbox.append(it, testEvent(aggregate = event.aggregateId))
                    }.aggregateSequence shouldBe 2L
            }
        }

        test("V2 historical JSON objects migrate additively and can be claimed and confirmed") {
            withTestDatabase { _, pool ->
                Flyway
                    .configure()
                    .dataSource(pool)
                    .defaultSchema("commerce")
                    .schemas("commerce")
                    .locations("classpath:db/commerce")
                    .target("2")
                    .load()
                    .migrate()
                val event = testEvent(aggregate = "legacy", payload = """{ "nested": {"unicode":"☕"}, "number":1.00 }""")
                val transactor = Transactor(Jdbi.create(pool))
                transactor.inTransaction { tx ->
                    tx.handle.execute("INSERT INTO commerce.outbox_aggregates VALUES ('example', 'record', 'legacy', 1)")
                    tx.handle
                        .createUpdate(legacyInsert)
                        .bind("id", event.id)
                        .bind("payload", event.payload.toString())
                        .execute()
                }
                val recordedAt = pool.strings("SELECT recorded_at FROM commerce.outbox_events").single()
                MigrationLifecycle(pool).migrate()
                MigrationLifecycle(pool).validate()
                pool.appliedVersions("commerce") shouldBe listOf("1", "2", "3")
                pool.strings("SELECT recorded_at FROM commerce.outbox_events").single() shouldBe recordedAt
                val publisher = Outbox(transactor).publisher("example", "new")
                val claim = publisher.claim(1, Duration.ofMinutes(1)).single()
                claim.record.event shouldBe event
                claim.record.aggregateSequence shouldBe 1L
                claim.record.payloadJson shouldBe pool.strings("SELECT payload::text FROM commerce.outbox_events").single()
                publisher.confirm(claim) shouldBe true
            }
        }

        test("invalid or non-object V2 history fails migration without discarding historical content") {
            listOf("invalid JSON", "[]").forEach { payload ->
                withTestDatabase { _, pool ->
                    Flyway
                        .configure()
                        .dataSource(pool)
                        .defaultSchema("commerce")
                        .schemas("commerce")
                        .locations("classpath:db/commerce")
                        .target("2")
                        .load()
                        .migrate()
                    pool.execute("INSERT INTO commerce.outbox_aggregates VALUES ('example', 'record', 'legacy', 1)")
                    Jdbi.create(pool).useHandle<Exception> { handle ->
                        handle
                            .createUpdate(legacyInsert)
                            .bind("id", UUID.randomUUID())
                            .bind("payload", payload)
                            .execute()
                    }
                    shouldThrow<Exception> { MigrationLifecycle(pool).migrate() }
                    pool.appliedVersions("commerce") shouldBe listOf("1", "2")
                    pool.strings("SELECT payload FROM commerce.outbox_events").single() shouldBe payload
                }
            }
        }

        test("factory receives each authoritative sequence once before commit and claims retain it") {
            withOutbox { fixture, outbox ->
                val draft = testEvent(aggregate = "same").draft()
                val invoked = mutableListOf<Long>()
                fixture.transactor.inTransaction { tx ->
                    repeat(3) {
                        val record =
                            outbox.append(tx, draft.copy(id = UUID.randomUUID())) { sequence ->
                                invoked.add(sequence)
                                sequencePayload(sequence)
                            }
                        record.event.payload["sequence"]!!
                            .jsonPrimitive.long shouldBe record.aggregateSequence
                        fixture.onOtherThread { outbox.backlog("example").unpublished shouldBe 0L }
                    }
                }
                invoked shouldBe listOf(1L, 2L, 3L)
                val publisher = outbox.publisher("example", "one")
                repeat(3) { index ->
                    val claim = publisher.claim(100, Duration.ofMinutes(1)).single()
                    claim.record.aggregateSequence shouldBe index + 1L
                    claim.record.event.payload["sequence"]!!
                        .jsonPrimitive.long shouldBe claim.record.aggregateSequence
                    publisher.confirm(claim) shouldBe true
                }
            }
        }

        test("factory, validation, insertion, and later business failures roll back writes and counter") {
            withOutbox { fixture, outbox ->
                val draft = testEvent(aggregate = "same").draft()
                var calls = 0
                val factories: List<(Long) -> JsonObject> =
                    listOf(
                        { error("factory failed") },
                        { buildJsonObject { put("value", "\u0000") } },
                        { buildJsonObject { put("\uD800", "invalid key") } },
                    )
                factories.forEach { factory ->
                    shouldThrow<RuntimeException> {
                        fixture.transactor.inTransaction { tx ->
                            tx.handle
                                .createUpdate("INSERT INTO testapp.test_application_records VALUES (:id, 'business')")
                                .bind("id", UUID.randomUUID())
                                .execute()
                            outbox.append(tx, draft) { sequence ->
                                calls++
                                sequence shouldBe 1L
                                factory(sequence)
                            }
                        }
                    }
                    outbox.backlog("example").unpublished shouldBe 0L
                    fixture.dataSource.strings("SELECT count(*) FROM commerce.outbox_aggregates").single() shouldBe "0"
                    fixture.dataSource.strings("SELECT count(*) FROM testapp.test_application_records").single() shouldBe "0"
                }
                calls shouldBe factories.size
                fixture.transactor.inTransaction { outbox.append(it, draft, ::sequencePayload) }.aggregateSequence shouldBe 1L
                shouldThrow<CommerceFailure.Conflict> {
                    fixture.transactor.inTransaction { tx ->
                        outbox.append(tx, draft) { sequence ->
                            calls++
                            sequence shouldBe 2L
                            sequencePayload(sequence)
                        }
                    }
                }
                calls shouldBe factories.size + 1
                shouldThrow<Exception> {
                    fixture.transactor.inTransaction { tx ->
                        outbox.append(tx, draft.copy(id = UUID.randomUUID()), ::sequencePayload).aggregateSequence shouldBe 2L
                        tx.handle.execute("INSERT INTO testapp.test_application_records VALUES (NULL, 'fails after append')")
                    }
                }
                fixture.transactor
                    .inTransaction {
                        outbox.append(it, draft.copy(id = UUID.randomUUID()), ::sequencePayload)
                    }.aggregateSequence shouldBe 2L
                fixture.dataSource.strings("SELECT payload->>'sequence' FROM commerce.outbox_events ORDER BY aggregate_sequence") shouldBe
                    listOf("1", "2")
            }
        }
    })

internal fun sequencePayload(sequence: Long) = buildJsonObject { put("sequence", sequence) }

private val legacyInsert =
    """INSERT INTO commerce.outbox_events
    (id, producer_id, aggregate_type, aggregate_id, aggregate_sequence,
     event_type, schema_version, destination, payload, occurred_at)
    VALUES (:id, 'example', 'record', 'legacy', 1, 'RecordSaved', 1, 'events.records', :payload, '1970-01-01Z')"""
