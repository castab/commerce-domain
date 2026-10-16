package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.outbox.OutboxBacklog
import io.github.castab.commerce.runtime.outbox.OutboxClaim
import io.github.castab.commerce.runtime.outbox.OutboxEvent
import io.github.castab.commerce.runtime.outbox.OutboxEventDraft
import io.github.castab.commerce.runtime.outbox.OutboxFailureCode
import io.github.castab.commerce.runtime.outbox.RecordedOutboxEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.ResultSet
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Runtime persistence only; the public semantic surface is context.outbox. */
internal class OutboxRepository {
    fun append(
        transaction: Transaction,
        draft: OutboxEventDraft,
        payloadFactory: (Long) -> JsonObject,
    ): RecordedOutboxEvent {
        try {
            val sequence =
                transaction.handle
                    .createQuery(
                        """INSERT INTO commerce.outbox_aggregates (producer_id, aggregate_type, aggregate_id, last_sequence)
                       VALUES (:producer, :type, :aggregate, 1)
                       ON CONFLICT (producer_id, aggregate_type, aggregate_id)
                       DO UPDATE SET last_sequence = commerce.outbox_aggregates.last_sequence + 1
                       RETURNING last_sequence""",
                    ).bind("producer", draft.producerId)
                    .bind("type", draft.aggregateType)
                    .bind("aggregate", draft.aggregateId)
                    .mapTo(Long::class.java)
                    .one()
            val event = draft.event(payloadFactory(sequence))
            return transaction.handle
                .createQuery(
                    """INSERT INTO commerce.outbox_events
                   (id, producer_id, aggregate_type, aggregate_id, aggregate_sequence,
                    event_type, schema_version, destination, payload, occurred_at)
                   VALUES (:id, :producer, :type, :aggregate, :sequence, :eventType,
                           :version, :destination, CAST(:payload AS jsonb), :occurred)
                   RETURNING *""",
                ).bind("id", event.id)
                .bind("producer", draft.producerId)
                .bind("type", draft.aggregateType)
                .bind("aggregate", draft.aggregateId)
                .bind("sequence", sequence)
                .bind("eventType", event.eventType)
                .bind("version", event.schemaVersion)
                .bind("destination", event.destination)
                .bind("payload", event.payload.toString())
                .bind("occurred", event.occurredAt.atOffset(ZoneOffset.UTC))
                .map { rows, _ -> record(rows) }
                .one()
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Outbox event ID is already present", e)
            if (e.isSerializationFailure()) {
                throw CommerceFailure.Conflict("Outbox aggregate changed; retry the entire transaction", e)
            }
            throw e
        }
    }

    fun claim(
        transaction: Transaction,
        producerId: String,
        publisherId: String,
        batchSize: Int,
        lease: Duration,
    ): List<OutboxClaim> {
        deliveryTimeout(transaction)
        return transaction.handle
            .createQuery(
                """WITH candidates AS (
                 SELECT e.id FROM commerce.outbox_events e
                 WHERE e.producer_id = :producer AND e.published_at IS NULL
                   AND e.next_attempt_at <= statement_timestamp()
                   AND (e.claimed_until IS NULL OR e.claimed_until <= statement_timestamp())
                   AND NOT EXISTS (
                     SELECT 1 FROM commerce.outbox_events predecessor
                     WHERE predecessor.producer_id = e.producer_id
                       AND predecessor.aggregate_type = e.aggregate_type
                       AND predecessor.aggregate_id = e.aggregate_id
                       AND predecessor.aggregate_sequence < e.aggregate_sequence
                       AND predecessor.published_at IS NULL
                   )
                 ORDER BY e.recorded_at, e.id
                 LIMIT :batch FOR UPDATE OF e SKIP LOCKED
               )
               UPDATE commerce.outbox_events e
               SET claim_token = gen_random_uuid(), claimed_by = :publisher,
                   claimed_until = statement_timestamp() + (:lease * interval '1 millisecond'),
                   attempts = attempts + 1
               FROM candidates WHERE e.id = candidates.id
               RETURNING e.*""",
            ).bind("producer", producerId)
            .bind("publisher", publisherId)
            .bind("lease", lease.toMillis())
            .bind("batch", batchSize)
            .map { rows, _ ->
                OutboxClaim(
                    record(rows),
                    rows.getObject("claim_token", UUID::class.java),
                    rows.getLong("attempts"),
                    rows.getObject("claimed_until", OffsetDateTime::class.java).toInstant(),
                )
            }.list()
    }

    fun confirm(
        transaction: Transaction,
        producerId: String,
        claim: OutboxClaim,
    ): Boolean {
        deliveryTimeout(transaction)
        return transaction.handle
            .createUpdate(
                """UPDATE commerce.outbox_events
               SET published_at = clock_timestamp(), claim_token = NULL, claimed_by = NULL,
                   claimed_until = NULL, last_failure_code = NULL
               WHERE id = :id AND producer_id = :producer AND claim_token = :token
                 AND published_at IS NULL AND claimed_until > clock_timestamp()""",
            ).bind("id", claim.record.event.id)
            .bind("producer", producerId)
            .bind("token", claim.token)
            .execute() == 1
    }

    fun fail(
        transaction: Transaction,
        producerId: String,
        claim: OutboxClaim,
        code: OutboxFailureCode,
        retry: Duration,
    ): Boolean {
        deliveryTimeout(transaction)
        return transaction.handle
            .createUpdate(
                """UPDATE commerce.outbox_events
               SET next_attempt_at = clock_timestamp() + (:retry * interval '1 millisecond'),
                   last_failure_code = :code, claim_token = NULL, claimed_by = NULL, claimed_until = NULL
               WHERE id = :id AND producer_id = :producer AND claim_token = :token
                 AND published_at IS NULL AND claimed_until > clock_timestamp()""",
            ).bind("id", claim.record.event.id)
            .bind("producer", producerId)
            .bind("token", claim.token)
            .bind("code", code.name)
            .bind("retry", retry.toMillis())
            .execute() == 1
    }

    fun backlog(
        transaction: Transaction,
        producerId: String,
    ): OutboxBacklog =
        transaction.handle
            .createQuery(
                """SELECT count(*) AS unpublished, min(recorded_at) AS oldest,
                 count(*) FILTER (WHERE claimed_until > statement_timestamp()) AS claimed,
                 count(*) FILTER (WHERE next_attempt_at > statement_timestamp()) AS waiting,
                 count(*) FILTER (WHERE last_failure_code IS NOT NULL) AS failed,
                 count(*) FILTER (
                   WHERE next_attempt_at <= statement_timestamp()
                     AND (claimed_until IS NULL OR claimed_until <= statement_timestamp())
                     AND NOT EXISTS (
                       SELECT 1 FROM commerce.outbox_events p
                       WHERE p.producer_id = e.producer_id AND p.aggregate_type = e.aggregate_type
                         AND p.aggregate_id = e.aggregate_id AND p.aggregate_sequence < e.aggregate_sequence
                         AND p.published_at IS NULL
                     )
                 ) AS eligible
               FROM commerce.outbox_events e WHERE producer_id = :producer AND published_at IS NULL""",
            ).bind("producer", producerId)
            .map { rows, _ ->
                OutboxBacklog(
                    rows.getLong("unpublished"),
                    rows.getObject("oldest", OffsetDateTime::class.java)?.toInstant(),
                    rows.getLong("claimed"),
                    rows.getLong("eligible"),
                    rows.getLong("waiting"),
                    rows.getLong("failed"),
                )
            }.one()

    private fun deliveryTimeout(transaction: Transaction) {
        transaction.handle.execute("SET LOCAL statement_timeout = '5s'")
    }

    private fun record(rows: ResultSet): RecordedOutboxEvent =
        RecordedOutboxEvent(
            OutboxEvent(
                rows.getObject("id", UUID::class.java),
                rows.getString("producer_id"),
                rows.getString("aggregate_type"),
                rows.getString("aggregate_id"),
                rows.getString("event_type"),
                rows.getInt("schema_version"),
                rows.getString("destination"),
                Json.parseToJsonElement(rows.getString("payload")).jsonObject,
                rows.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
            ),
            rows.getLong("aggregate_sequence"),
            rows.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
            rows.getString("payload"),
        )
}
