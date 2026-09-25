package io.github.castab.commerce.booking

import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.customer.PhoneNumber
import java.util.UUID

/** Identity of one independently stored booking contact. */
data class BookingContactId(
    val value: UUID,
)

/** Operational contact for exactly one booking; never embeds a booking or customer. */
sealed interface BookingContact {
    val id: BookingContactId
    val bookingId: BookingId

    /** A contact resolved through the durable customer identity, without duplicated PII. */
    data class CustomerContact(
        override val id: BookingContactId,
        override val bookingId: BookingId,
        val customerId: Customer.Id,
    ) : BookingContact

    /** A booking-specific person who need not become a durable customer. */
    data class ExternalContact(
        override val id: BookingContactId,
        override val bookingId: BookingId,
        val name: CustomerName,
        val email: EmailAddress? = null,
        val phoneNumber: PhoneNumber? = null,
    ) : BookingContact {
        init {
            require(email != null || phoneNumber != null) {
                "External booking contact $id must have an email address or phone number"
            }
        }
    }
}
