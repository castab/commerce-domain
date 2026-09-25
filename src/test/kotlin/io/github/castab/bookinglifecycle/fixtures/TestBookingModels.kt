package io.github.castab.bookinglifecycle.fixtures

import io.github.castab.bookinglifecycle.BookingLifecycle

/*
 * Test-only "application-owned" domain models.
 *
 * They carry arbitrary data of their own to show that the lifecycle protocol
 * places no requirements on an adopter's domain model, and they declare
 * concrete (covariant) return types on every transition.
 */

data class TestInitialRequest(
    val requester: String,
    val partySize: Int,
    val notes: List<String> = emptyList(),
) : BookingLifecycle.Active.InitialRequest {

    override fun toQuote(): TestQuote =
        TestQuote(
            requester = requester,
            partySize = partySize,
            quotedCents = partySize * PRICE_PER_GUEST_CENTS,
        )

    override fun cancel(): CancelledInquiry =
        CancelledInquiry(requester = requester, reason = "requester withdrew")

    companion object {
        const val PRICE_PER_GUEST_CENTS: Long = 2_500
    }
}

data class TestQuote(
    val requester: String,
    val partySize: Int,
    val quotedCents: Long,
    val revision: Int = 1,
) : BookingLifecycle.Active.Quote {

    /** An application-owned revision. The result is still a [BookingLifecycle.Active.Quote]. */
    fun revise(newQuotedCents: Long): TestQuote =
        copy(quotedCents = newQuotedCents, revision = revision + 1)

    override fun toBooking(): TestBooking =
        TestBooking(
            requester = requester,
            partySize = partySize,
            agreedCents = quotedCents,
            confirmationCode = "CONF-$requester-r$revision",
        )

    override fun cancel(): DeclinedQuote =
        DeclinedQuote(requester = requester, declinedCents = quotedCents)
}

data class TestBooking(
    val requester: String,
    val partySize: Int,
    val agreedCents: Long,
    val confirmationCode: String,
) : BookingLifecycle.Active.Booked {

    override fun complete(): TestCompletedBooking =
        TestCompletedBooking(confirmationCode = confirmationCode, finalCents = agreedCents)

    override fun cancel(): CancelledBooking =
        CancelledBooking(confirmationCode = confirmationCode, forfeitedCents = agreedCents / 10)
}

data class TestCompletedBooking(
    val confirmationCode: String,
    val finalCents: Long,
) : BookingLifecycle.Terminal.Completed

data class CancelledInquiry(
    val requester: String,
    val reason: String,
) : BookingLifecycle.Terminal.Cancelled

data class DeclinedQuote(
    val requester: String,
    val declinedCents: Long,
) : BookingLifecycle.Terminal.Cancelled

data class CancelledBooking(
    val confirmationCode: String,
    val forfeitedCents: Long,
) : BookingLifecycle.Terminal.Cancelled

/**
 * A different adopter whose quote-to-booking transition has application-owned
 * business prerequisites. The lifecycle protocol knows nothing about them.
 */
data class GuardedQuote(
    val reference: String,
    val customerAccepted: Boolean,
) : BookingLifecycle.Active.Quote {

    override fun toBooking(): TestBooking {
        require(customerAccepted) { "Quote $reference has not been accepted by the customer" }
        return TestBooking(
            requester = reference,
            partySize = 1,
            agreedCents = 0,
            confirmationCode = "CONF-$reference",
        )
    }

    override fun cancel(): DeclinedQuote = DeclinedQuote(requester = reference, declinedCents = 0)
}
