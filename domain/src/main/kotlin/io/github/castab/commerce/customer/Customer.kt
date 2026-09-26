package io.github.castab.commerce.customer

import java.util.UUID

/** A person's required name, without jurisdiction-specific parsing. */
data class CustomerName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Customer name must not be blank" }
    }
}

/** An email address. Syntax and deliverability checks belong to the application. */
data class EmailAddress(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Email address must not be blank" }
    }
}

/** A phone number in the application's chosen international or local notation. */
data class PhoneNumber(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Phone number must not be blank" }
    }
}

/** Minimal durable identity of the person doing business with an application; no operational phone number. */
data class Customer(
    val id: Id,
    val name: CustomerName,
    val email: EmailAddress,
) {
    /** Stable identity shared by bookings and commercial documents. */
    data class Id(
        val value: UUID,
    )
}
