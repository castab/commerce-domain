package io.github.castab.commerce.booking

import io.github.castab.commerce.customer.Customer
import java.util.UUID

/**
 * The association between a booking and its customer. Lifecycle phase models remain
 * application-owned and may carry this record. Contacts and location are separate facts
 * looked up by [id], so they can be removed independently.
 */
data class Booking(
    val id: Id,
    val customerId: Customer.Id,
) {
    /** Stable identity of a booking across application-owned lifecycle phases. */
    data class Id(
        val value: UUID,
    )
}
