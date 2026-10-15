package io.github.castab.commerce.runtime

/**
 * An opt-in application contribution, constructed without starting background activity.
 * [start] must return promptly. [close] must be bounded, idempotent, and safe before start
 * and after a partially failed start. It must stop database work before returning.
 * Implementations own cleanup of resources allocated by a failed constructor/factory.
 */
interface ManagedBackgroundService : AutoCloseable {
    fun start()

    override fun close()
}
