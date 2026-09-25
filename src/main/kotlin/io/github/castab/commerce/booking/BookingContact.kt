package io.github.castab.commerce.booking

import io.github.castab.commerce.customer.CustomerId
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.customer.PhoneNumber
import java.util.UUID

/** Identity of one independently stored booking contact. */
public data class BookingContactId(public val value: UUID)

/** Operational contact for exactly one booking; never embeds a booking or customer. */
public sealed interface BookingContact {
    public val id: BookingContactId
    public val bookingId: BookingId

    /** A contact resolved through the durable customer identity, without duplicated PII. */
    public data class CustomerContact(
        override val id: BookingContactId,
        override val bookingId: BookingId,
        public val customerId: CustomerId,
    ) : BookingContact

    /** A booking-specific person who need not become a durable customer. */
    public data class ExternalContact(
        override val id: BookingContactId,
        override val bookingId: BookingId,
        public val name: CustomerName,
        public val email: EmailAddress? = null,
        public val phoneNumber: PhoneNumber? = null,
    ) : BookingContact {
        init {
            require(email != null || phoneNumber != null) {
                "External booking contact $id must have an email address or phone number"
            }
        }
    }
}
