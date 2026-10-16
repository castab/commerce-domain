package io.github.castab.commerce.runtime.outbox

import io.github.castab.commerce.runtime.persistence.OutboxRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Durable integration events for application business transactions. Available as
 * `CommerceRuntimeContext.outbox`; uses the existing runtime pool and transaction boundary.
 */
class Outbox internal constructor(
    private val transactor: Transactor,
    private val repository: OutboxRepository = OutboxRepository(),
) {
    /**
     * Appends once in the caller's transaction, allocating the next aggregate sequence.
     * A duplicate ID fails with `CommerceFailure.Conflict`, including identical content;
     * applications own command idempotency. Never performs network I/O or commits.
     * A stale REPEATABLE_READ snapshot conflicts; retry the entire caller transaction.
     * When appending to several aggregates, callers must use a consistent aggregate order
     * across transactions to avoid ordinary PostgreSQL deadlocks.
     */
    fun append(
        transaction: Transaction,
        event: OutboxEvent,
    ): RecordedOutboxEvent = repository.append(transaction, event.draft()) { event.payload }

    /**
     * Allocates the next positive sequence, then calls [payloadFactory] synchronously once
     * and inserts its JSON object in the caller's transaction. The returned content is read
     * from persisted JSONB. No callback retry is performed. Let failures escape the business
     * transaction so business writes, sequence allocation, and insertion roll back together.
     *
     * The factory must be deterministic for the supplied facts and sequence, with no external
     * side effects or network I/O. Retry conflicts by retrying the entire business operation.
     * The same duplicate-ID and aggregate lock-order rules as direct append apply.
     */
    fun append(
        transaction: Transaction,
        draft: OutboxEventDraft,
        payloadFactory: (Long) -> JsonObject,
    ): RecordedOutboxEvent = repository.append(transaction, draft, payloadFactory)

    /** A delivery coordinator bound explicitly to a logical producer and a process identity. */
    fun publisher(
        producerId: String,
        publisherId: String,
    ): OutboxPublisher = OutboxPublisher(transactor, repository, producerId, publisherId)

    /** One payload-free snapshot. Joins the caller's transaction without changing isolation. */
    fun backlog(
        transaction: Transaction,
        producerId: String,
    ): OutboxBacklog {
        outboxIdentifier(producerId)
        return repository.backlog(transaction, producerId)
    }

    /** One payload-free snapshot in a short transaction. */
    fun backlog(producerId: String): OutboxBacklog = transactor.inTransaction { backlog(it, producerId) }
}

/**
 * Claim/finalization operations for one producer. Each owns a short READ COMMITTED
 * transaction and rejects invocation inside an existing managed transaction, so a returned
 * claim is committed before delivery. Lease and retry timing use the database clock.
 */
class OutboxPublisher internal constructor(
    private val transactor: Transactor,
    private val repository: OutboxRepository,
    val producerId: String,
    val publisherId: String,
) {
    init {
        outboxIdentifier(producerId)
        outboxIdentifier(publisherId)
    }

    /**
     * Claims at most [batchSize] independent aggregate heads. Expired leases are reclaimed
     * here automatically; no recovery job is needed. Attempts count claims, including crashes.
     */
    fun claim(
        batchSize: Int,
        lease: Duration,
    ): List<OutboxClaim> {
        require(batchSize in 1..100) { "Outbox batch size must be between 1 and 100" }
        boundedDuration(lease)
        return transactor.inIndependentTransaction { repository.claim(it, producerId, publisherId, batchSize, lease) }.also {
            if (it.isNotEmpty()) logger.info { "event=outbox_events_claimed count=${it.size}" }
        }
    }

    /**
     * Call only after confirmed transport acceptance. Returns false when the lease expired,
     * was superseded, belongs to another producer, or was already finalized.
     */
    fun confirm(claim: OutboxClaim): Boolean =
        transactor.inIndependentTransaction { repository.confirm(it, producerId, claim) }.also {
            if (it) logger.info { "event=outbox_publication_confirmed event_id=${claim.record.event.id}" }
        }

    /** Releases this live lease and schedules retry; a stale claim changes nothing. */
    fun fail(
        claim: OutboxClaim,
        code: OutboxFailureCode,
        retryAfter: Duration,
    ): Boolean {
        boundedDuration(retryAfter)
        return transactor.inIndependentTransaction { repository.fail(it, producerId, claim, code, retryAfter) }.also {
            if (it) logger.warn { "event=outbox_publication_failed event_id=${claim.record.event.id} code=$code" }
        }
    }
}

internal fun boundedDuration(value: Duration) {
    require(value >= Duration.ofMillis(1) && value <= Duration.ofDays(1) && value.nano % 1_000_000 == 0) {
        "Outbox durations must be whole milliseconds between 1 ms and 1 day"
    }
}
