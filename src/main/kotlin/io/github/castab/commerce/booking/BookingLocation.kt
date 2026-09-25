package io.github.castab.commerce.booking

import java.util.UUID

/** Identity of one independently stored booking location. */
data class BookingLocationId(
    val value: UUID,
)

/** Postal or service address; field formats are intentionally jurisdiction-neutral. */
data class PostalAddress(
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val region: String? = null,
    val postalCode: String? = null,
    val countryCode: String? = null,
) {
    init {
        require(addressLine1.isNotBlank()) { "Address line 1 must not be blank" }
        require(addressLine2 == null || addressLine2.isNotBlank()) { "Address line 2 must not be blank" }
        require(city.isNotBlank()) { "City must not be blank" }
        require(region == null || region.isNotBlank()) { "Region must not be blank" }
        require(postalCode == null || postalCode.isNotBlank()) { "Postal code must not be blank" }
        require(countryCode == null || countryCode.isNotBlank()) { "Country code must not be blank" }
    }
}

/** Location for one booking, separate from customer identity and contact records. */
data class BookingLocation(
    val id: BookingLocationId,
    val bookingId: BookingId,
    val address: PostalAddress,
)
