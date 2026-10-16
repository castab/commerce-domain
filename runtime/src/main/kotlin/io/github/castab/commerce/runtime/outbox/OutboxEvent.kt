package io.github.castab.commerce.runtime.outbox

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Application-owned JSON object content, normalized and persisted as PostgreSQL JSONB.
 * IDs are globally unique and established by the caller once. No business semantics are
 * inferred from this envelope. Do not put transport credentials in it.
 *
 * [occurredAt] must have microsecond precision (PostgreSQL's precision). Payloads must be
 * JSON objects with Unicode strings and keys without NUL. [toString] deliberately excludes
 * all application content.
 */
data class OutboxEvent(
    val id: UUID,
    val producerId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val schemaVersion: Int,
    val destination: String,
    val payload: JsonObject,
    val occurredAt: Instant,
) {
    init {
        draft() // Validate metadata before persistence.
        validatePayload(payload)
    }

    internal fun draft(): OutboxEventDraft =
        OutboxEventDraft(id, producerId, aggregateType, aggregateId, eventType, schemaVersion, destination, occurredAt)

    override fun toString(): String = "OutboxEvent(id=$id)"
}

/** Metadata validated before allocating a sequence; applications own the JSON schema. */
data class OutboxEventDraft(
    val id: UUID,
    val producerId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val schemaVersion: Int,
    val destination: String,
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
    }

    internal fun event(payload: JsonObject): OutboxEvent =
        OutboxEvent(id, producerId, aggregateType, aggregateId, eventType, schemaVersion, destination, payload, occurredAt)

    override fun toString(): String = "OutboxEventDraft(id=$id)"
}

/** The immutable envelope plus its transactionally assigned ordering and append metadata. */
class RecordedOutboxEvent internal constructor(
    val event: OutboxEvent,
    val aggregateSequence: Long,
    val recordedAt: Instant,
    /**
     * PostgreSQL's stored JSONB text, the authoritative publication representation.
     * Send its UTF-8 bytes unchanged on every attempt; never reconstruct from business state.
     * Original producer whitespace, key order, and numeric formatting are not preserved.
     */
    val payloadJson: String,
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

private fun validatePayload(value: JsonElement) {
    when (value) {
        is JsonObject ->
            value.forEach { (key, child) ->
                validatePayloadString(key)
                validatePayload(child)
            }
        is JsonArray -> value.forEach(::validatePayload)
        is JsonPrimitive -> validatePayloadString(value.content)
    }
}

private fun validatePayloadString(value: String) {
    require('\u0000' !in value && value.isWellFormedUnicode()) { "Outbox JSON strings must be Unicode text without NUL" }
}
