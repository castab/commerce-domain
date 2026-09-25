package io.github.castab.commerce.customer

import java.util.UUID

/** Stable identity shared by bookings and commercial documents. */
public data class CustomerId(public val value: UUID)

/** A person's required name, without jurisdiction-specific parsing. */
public data class CustomerName(public val value: String) {
    init { require(value.isNotBlank()) { "Customer name must not be blank" } }
}

/** An email address. Syntax and deliverability checks belong to the application. */
public data class EmailAddress(public val value: String) {
    init { require(value.isNotBlank()) { "Email address must not be blank" } }
}

/** A phone number in the application's chosen international or local notation. */
public data class PhoneNumber(public val value: String) {
    init { require(value.isNotBlank()) { "Phone number must not be blank" } }
}

/** Minimal durable identity of the person doing business with an application. */
public data class Customer(
    public val id: CustomerId,
    public val name: CustomerName,
    public val email: EmailAddress,
    public val phoneNumber: PhoneNumber,
)
