package io.github.castab.commerce.runtime.outbox

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Application-owned integration content, persisted verbatim as UTF-8 PostgreSQL text.
 * IDs are globally unique and established by the caller once. No business semantics are
 * inferred from this envelope. Do not put transport credentials in it.
 *
 * [occurredAt] must have microsecond precision (PostgreSQL's precision). Payloads must be
 * valid Unicode text without NUL. [toString] deliberately excludes all application content.
 */
data class OutboxEvent(
    val id: UUID,
    val producerId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val schemaVersion: Int,
    val destination: String,
    val payload: String,
    val occurredAt: Instant,
) {
    init {
        outboxIdentifier(producerId)
        outboxIdentifier(aggregateType)
        outboxIdentifier(aggregateId)
        outboxIdentifier(eventType)
        outboxIdentifier(destination)
        require(schemaVersion > 0) { "Outbox schema version must be positive" }
        require(occurredAt == occurredAt.truncatedTo(ChronoUnit.MICROS)) { "Outbox timestamp must have microsecond precision" }
        require(occurredAt >= Instant.parse("0001-01-01T00:00:00Z") && occurredAt < Instant.parse("+10000-01-01T00:00:00Z")) {
            "Outbox timestamp must be in years 1 through 9999"
        }
        require('\u0000' !in payload && payload.isWellFormedUnicode()) { "Outbox payload must be Unicode text without NUL" }
    }

    override fun toString(): String = "OutboxEvent(id=$id)"
}

/** The immutable envelope plus its transactionally assigned ordering and append metadata. */
class RecordedOutboxEvent internal constructor(
    val event: OutboxEvent,
    val aggregateSequence: Long,
    val recordedAt: Instant,
)

/** One committed lease. Its token fences bookkeeping after another process reclaims it. */
class OutboxClaim internal constructor(
    val record: RecordedOutboxEvent,
    val token: UUID,
    val attempts: Long,
    val claimedUntil: Instant,
)

/** Safe diagnostics: never exception messages, payloads, or connection settings. */
enum class OutboxFailureCode {
    UNCONFIRMED,
    TRANSPORT_EXCEPTION,
    TIMEOUT,
}

/** Counts at one database statement's snapshot, for one logical producer; contains no payloads. */
data class OutboxBacklog(
    val unpublished: Long,
    val oldestUnpublishedAt: Instant?,
    val claimed: Long,
    val retryEligible: Long,
    val waitingForRetry: Long,
    val failed: Long,
)

internal fun outboxIdentifier(value: String) {
    require(value.length in 1..200 && value.isNotBlank() && value == value.trim() && value.none { it.isISOControl() }) {
        "Outbox identifiers must contain 1 to 200 characters without surrounding whitespace or control characters"
    }
    require(value.isWellFormedUnicode()) { "Outbox identifiers must be Unicode text" }
}

private fun String.isWellFormedUnicode(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index++]
        if (char.isHighSurrogate()) {
            if (index == length || !this[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) {
            return false
        }
    }
    return true
}
