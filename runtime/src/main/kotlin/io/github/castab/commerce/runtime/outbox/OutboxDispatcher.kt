package io.github.castab.commerce.runtime.outbox

import io.github.castab.commerce.runtime.ManagedBackgroundService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/** Only CONFIRMED means the adapter verified durable transport acceptance. */
enum class PublicationResult {
    CONFIRMED,
    UNCONFIRMED,
}

/**
 * Application-supplied delivery. Use the unchanged event ID as the transport deduplication
 * identity. Return CONFIRMED only after verifying the broker's positive acknowledgment;
 * sending bytes is insufficient. Configure adapter I/O timeouts and honor interruption.
 * No Transaction is passed: publication always happens outside database transactions.
 */
fun interface OutboxTransport {
    fun publish(event: RecordedOutboxEvent): PublicationResult
}

/** Polling and timing policy supplied by the application, never deployment defaults in resources. */
data class OutboxDispatchSettings(
    val batchSize: Int = 10,
    val pollInterval: Duration = Duration.ofSeconds(1),
    val lease: Duration = Duration.ofMinutes(2),
    val publishTimeout: Duration = Duration.ofSeconds(5),
    val retryInitial: Duration = Duration.ofSeconds(1),
    val retryMaximum: Duration = Duration.ofMinutes(5),
) {
    init {
        require(batchSize in 1..100) { "Outbox batch size must be between 1 and 100" }
        listOf(pollInterval, lease, publishTimeout, retryInitial, retryMaximum).forEach(::boundedDuration)
        require(retryMaximum >= retryInitial) { "Outbox maximum retry must be at least the initial retry" }
        // Sequential bounded batches: allow 5 seconds per DB finalization plus claim/commit margin.
        require(lease > publishTimeout.plusSeconds(5).multipliedBy(batchSize.toLong()).plusSeconds(10)) {
            "Outbox lease must exceed batchSize * (publishTimeout + 5 seconds) + 10 seconds"
        }
    }

    /** Saturating exponential backoff. No attempt threshold discards or suspends events. */
    fun retryDelay(attempts: Long): Duration {
        require(attempts > 0) { "Outbox attempt count must be positive" }
        var millis = retryInitial.toMillis()
        repeat(minOf(attempts - 1, 47).toInt()) { millis = minOf(millis * 2, retryMaximum.toMillis()) }
        return Duration.ofMillis(millis)
    }
}

/**
 * A bounded polling worker using committed leases and an application transport. A timed-out
 * transport is interrupted and its event retried with the same envelope. One isolated,
 * daemon virtual transport thread is allowed; a transport ignoring interruption cannot
 * create unbounded threads or prevent shutdown. Such an adapter must be repaired/restarted.
 * Transport exceptions and database outages never escape into business operations.
 */
class OutboxDispatcher(
    private val publisher: OutboxPublisher,
    private val transport: OutboxTransport,
    private val settings: OutboxDispatchSettings = OutboxDispatchSettings(),
) : ManagedBackgroundService {
    private val monitor = Any()

    @Volatile private var running = false
    private var closed = false
    private var worker: Thread? = null
    private val transportExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            Thread.ofVirtual().name("commerce-outbox-transport").factory(),
        )

    override fun start() {
        synchronized(monitor) {
            check(!closed) { "Outbox dispatcher is closed" }
            if (running) return
            running = true
            worker =
                Thread
                    .ofVirtual()
                    .name("commerce-outbox-dispatcher")
                    .unstarted { runLoop() }
                    .also { it.start() }
        }
        logger.info { "event=outbox_dispatcher_started" }
    }

    private fun runLoop() {
        try {
            while (running) {
                try {
                    val claims = publisher.claim(settings.batchSize, settings.lease)
                    for (claim in claims) {
                        if (!running) break
                        deliver(claim)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // Exception text can include SQL bindings, payloads, or adapter secrets.
                    logger.warn { "event=outbox_dispatch_failed code=DATABASE_OR_COORDINATION_FAILURE" }
                }
                if (running) Thread.sleep(settings.pollInterval)
            }
        } catch (_: InterruptedException) {
            // Shutdown leaves unfinished leases durable and recoverable.
        } finally {
            running = false
            logger.info { "event=outbox_dispatcher_stopped" }
        }
    }

    private fun deliver(claim: OutboxClaim) {
        var attempt: Future<PublicationResult>? = null
        val failure =
            try {
                attempt = transportExecutor.submit<PublicationResult> { transport.publish(claim.record) }
                when (attempt.get(settings.publishTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    PublicationResult.CONFIRMED -> null
                    else -> OutboxFailureCode.UNCONFIRMED
                }
            } catch (_: TimeoutException) {
                OutboxFailureCode.TIMEOUT
            } catch (_: ExecutionException) {
                OutboxFailureCode.TRANSPORT_EXCEPTION
            } catch (_: RejectedExecutionException) {
                OutboxFailureCode.TIMEOUT
            } finally {
                attempt?.cancel(true)
                transportExecutor.purge()
            }
        if (!running) return
        if (failure == null) {
            publisher.confirm(claim)
        } else {
            publisher.fail(claim, failure, settings.retryDelay(claim.attempts))
        }
    }

    override fun close() {
        val thread =
            synchronized(monitor) {
                if (closed) return
                closed = true
                running = false
                worker?.also { it.interrupt() }
            }
        transportExecutor.shutdownNow()
        // JDBC waits are bounded by the pool connection timeout and the five-second SQL
        // statement timeout. Joining ensures no dispatch DB work survives pool shutdown.
        thread?.join(Duration.ofSeconds(15))
        check(thread?.isAlive != true) { "Outbox dispatcher did not stop within 15 seconds" }
    }
}
