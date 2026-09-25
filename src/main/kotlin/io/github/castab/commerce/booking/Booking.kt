package io.github.castab.commerce.booking

import io.github.castab.commerce.customer.CustomerId
import java.util.UUID

/** Stable identity of a booking across application-owned lifecycle phases. */
public data class BookingId(public val value: UUID)

/**
 * The association between a booking and its customer. Lifecycle phase models remain
 * application-owned and may carry this record. Contacts and location are separate facts
 * looked up by [id], so they can be removed independently.
 */
public data class Booking(public val id: BookingId, public val customerId: CustomerId)
