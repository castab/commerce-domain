package io.github.castab.commerce.booking

import java.util.UUID

/** Identity of one independently stored booking location. */
public data class BookingLocationId(public val value: UUID)

/** Postal or service address; field formats are intentionally jurisdiction-neutral. */
public data class PostalAddress(
    public val addressLine1: String,
    public val addressLine2: String? = null,
    public val city: String,
    public val region: String? = null,
    public val postalCode: String? = null,
    public val countryCode: String? = null,
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
public data class BookingLocation(
    public val id: BookingLocationId,
    public val bookingId: BookingId,
    public val address: PostalAddress,
)
