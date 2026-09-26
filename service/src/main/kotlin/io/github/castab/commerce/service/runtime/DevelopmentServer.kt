package io.github.castab.commerce.service.runtime

import io.github.castab.commerce.service.config.CommerceServiceConfiguration

/**
 * Runs the generic commerce service with no application contributions, for local
 * development (`./gradlew :service:run`) against the database named by the environment.
 *
 * This is not a deployment. Concrete applications write their own `main`, calling
 * [commerceService] with their [ApplicationContributions].
 */
fun main() {
    val service = commerceService(CommerceServiceConfiguration.load()).start()
    Runtime.getRuntime().addShutdownHook(Thread { service.close() })
    Thread.currentThread().join()
}
