package io.github.castab.commerce.booking.lifecycle

import io.github.castab.commerce.booking.lifecycle.fixtures.CancelledBooking
import io.github.castab.commerce.booking.lifecycle.fixtures.CancelledInquiry
import io.github.castab.commerce.booking.lifecycle.fixtures.DeclinedQuote
import io.github.castab.commerce.booking.lifecycle.fixtures.GuardedQuote
import io.github.castab.commerce.booking.lifecycle.fixtures.TestBooking
import io.github.castab.commerce.booking.lifecycle.fixtures.TestCompletedBooking
import io.github.castab.commerce.booking.lifecycle.fixtures.TestInitialRequest
import io.github.castab.commerce.booking.lifecycle.fixtures.TestQuote
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.lang.reflect.Modifier

/*
 * Intentionally invalid API usage. None of these compile, because the operations
 * are absent from the phase interfaces — that absence is the enforcement mechanism:
 *
 *     initialRequest.complete()   // InitialRequest has no complete()
 *     initialRequest.toBooking()  // InitialRequest has no toBooking()
 *     quote.complete()            // Quote has no complete()
 *     completed.cancel()          // Terminal phases expose no transitions
 *     completed.toBooking()
 *     cancelled.toQuote()
 */

/** Compiles without an `else` branch only because [BookingLifecycle] is sealed. */
private fun classify(phase: BookingLifecycle): String =
    when (phase) {
        is BookingLifecycle.Active -> "active"
        is BookingLifecycle.Terminal -> "terminal"
    }

/** Compiles without an `else` branch only because [BookingLifecycle.Active] is sealed. */
private fun describe(phase: BookingLifecycle.Active): String =
    when (phase) {
        is BookingLifecycle.Active.InitialRequest -> "initial request"
        is BookingLifecycle.Active.Quote -> "quote"
        is BookingLifecycle.Active.Booked -> "booked"
    }

private fun Class<*>.transitionNames(): List<String> = declaredMethods.map { it.name }

class BookingLifecycleSpec : FunSpec({

    val request = TestInitialRequest(requester = "ada", partySize = 4, notes = listOf("vegetarian"))

    context("hierarchy") {

        test("application-owned active phases are Active BookingLifecycle members") {
            val quote = request.toQuote()
            val booking = quote.toBooking()

            listOf<BookingLifecycle>(request, quote, booking).forEach { phase ->
                phase.shouldBeInstanceOf<BookingLifecycle.Active>()
                phase.shouldNotBeInstanceOf<BookingLifecycle.Terminal>()
                classify(phase) shouldBe "active"
            }

            request.shouldBeInstanceOf<BookingLifecycle.Active.InitialRequest>()
            quote.shouldBeInstanceOf<BookingLifecycle.Active.Quote>()
            booking.shouldBeInstanceOf<BookingLifecycle.Active.Booked>()

            describe(request) shouldBe "initial request"
            describe(quote) shouldBe "quote"
            describe(booking) shouldBe "booked"
        }

        test("application-owned terminal phases are Terminal BookingLifecycle members") {
            val booking = request.toQuote().toBooking()
            val terminals = listOf<BookingLifecycle>(
                booking.complete(),
                request.cancel(),
                request.toQuote().cancel(),
                booking.cancel(),
            )

            terminals.forEach { phase ->
                phase.shouldBeInstanceOf<BookingLifecycle.Terminal>()
                phase.shouldNotBeInstanceOf<BookingLifecycle.Active>()
                classify(phase) shouldBe "terminal"
            }

            booking.complete().shouldBeInstanceOf<BookingLifecycle.Terminal.Completed>()
            booking.cancel().shouldBeInstanceOf<BookingLifecycle.Terminal.Cancelled>()
        }
    }

    context("legal lifecycle path") {

        test("InitialRequest -> Quote -> Booked -> Completed propagates application data") {
            val quote = request.toQuote()
            val booking = quote.toBooking()
            val completed = booking.complete()

            quote shouldBe TestQuote(
                requester = "ada",
                partySize = 4,
                quotedCents = 4 * TestInitialRequest.PRICE_PER_GUEST_CENTS,
            )
            booking shouldBe TestBooking(
                requester = "ada",
                partySize = 4,
                agreedCents = 10_000,
                confirmationCode = "CONF-ada-r1",
            )
            completed shouldBe TestCompletedBooking(confirmationCode = "CONF-ada-r1", finalCents = 10_000)
        }

        test("the canonical path reads as a fluent chain") {
            request.toQuote().toBooking().complete().confirmationCode shouldBe "CONF-ada-r1"
        }

        test("quote revisions are application data and remain Quote") {
            val revised = request.toQuote().revise(9_000).revise(8_500)

            revised.revision shouldBe 3
            revised.shouldBeInstanceOf<BookingLifecycle.Active.Quote>()
            revised.toBooking().agreedCents shouldBe 8_500
        }
    }

    context("cancellation from every active phase") {

        test("InitialRequest -> Cancelled") {
            val cancelled: CancelledInquiry = request.cancel()

            cancelled shouldBe CancelledInquiry(requester = "ada", reason = "requester withdrew")
            cancelled.shouldBeInstanceOf<BookingLifecycle.Terminal.Cancelled>()
        }

        test("Quote -> Cancelled") {
            val cancelled: DeclinedQuote = request.toQuote().cancel()

            cancelled shouldBe DeclinedQuote(requester = "ada", declinedCents = 10_000)
            cancelled.shouldBeInstanceOf<BookingLifecycle.Terminal.Cancelled>()
        }

        test("Booked -> Cancelled") {
            val cancelled: CancelledBooking = request.toQuote().toBooking().cancel()

            cancelled shouldBe CancelledBooking(confirmationCode = "CONF-ada-r1", forfeitedCents = 1_000)
            cancelled.shouldBeInstanceOf<BookingLifecycle.Terminal.Cancelled>()
        }
    }

    context("covariant concrete return types") {

        test("transitions return the adopter's concrete types without casts") {
            // Each explicit type annotation below only compiles because the fixture
            // overrides the transition with a more specific return type.
            val quote: TestQuote = request.toQuote()
            val booking: TestBooking = quote.toBooking()
            val completed: TestCompletedBooking = booking.complete()
            val cancelled: CancelledBooking = booking.cancel()

            quote.quotedCents shouldBe 10_000
            booking.confirmationCode shouldBe "CONF-ada-r1"
            completed.finalCents shouldBe 10_000
            cancelled.forfeitedCents shouldBe 1_000
        }

        test("through the protocol type, callers see the protocol return type") {
            val quote: BookingLifecycle.Active.Quote = request.toQuote()
            val booking: BookingLifecycle.Active.Booked = quote.toBooking()

            booking.shouldBeInstanceOf<TestBooking>()
        }
    }

    context("direct Quote starting point") {

        test("a Quote can be constructed without an InitialRequest") {
            val quote = TestQuote(requester = "grace", partySize = 2, quotedCents = 7_500)

            quote.shouldBeInstanceOf<BookingLifecycle.Active.Quote>()
            quote.toBooking().complete() shouldBe
                TestCompletedBooking(confirmationCode = "CONF-grace-r1", finalCents = 7_500)
        }
    }

    context("application-owned transition logic") {

        test("business prerequisites are enforced by the adopter, not the protocol") {
            val accepted = GuardedQuote(reference = "Q-1", customerAccepted = true)
            val pending = GuardedQuote(reference = "Q-2", customerAccepted = false)

            accepted.toBooking().confirmationCode shouldBe "CONF-Q-1"
            shouldThrow<IllegalArgumentException> { pending.toBooking() }
                .message shouldContain "not been accepted"
        }
    }

    context("protocol shape") {

        test("active phases declare exactly their legal outgoing edges") {
            BookingLifecycle.Active.InitialRequest::class.java.transitionNames()
                .shouldContainExactlyInAnyOrder("toQuote", "cancel")
            BookingLifecycle.Active.Quote::class.java.transitionNames()
                .shouldContainExactlyInAnyOrder("toBooking", "cancel")
            BookingLifecycle.Active.Booked::class.java.transitionNames()
                .shouldContainExactlyInAnyOrder("complete", "cancel")
        }

        test("every transition is abstract and has no library-provided implementation") {
            listOf(
                BookingLifecycle.Active.InitialRequest::class.java,
                BookingLifecycle.Active.Quote::class.java,
                BookingLifecycle.Active.Booked::class.java,
            ).forEach { phase ->
                phase.declaredMethods.forEach { method ->
                    Modifier.isAbstract(method.modifiers) shouldBe true
                    method.isDefault shouldBe false
                }
                phase.declaredClasses.shouldBeEmpty() // no DefaultImpls
            }
        }

        test("terminal phases declare no outgoing transitions") {
            listOf(
                BookingLifecycle.Terminal::class.java,
                BookingLifecycle.Terminal.Cancelled::class.java,
                BookingLifecycle.Terminal.Completed::class.java,
            ).forEach { terminal ->
                terminal.declaredMethods.shouldBeEmpty()
            }
        }
    }

    context("MockK") {

        test("lifecycle interfaces can be mocked, stubbed, and verified") {
            val completed = mockk<BookingLifecycle.Terminal.Completed>()
            val booked = mockk<BookingLifecycle.Active.Booked>()
            val quote = mockk<BookingLifecycle.Active.Quote>()

            every { quote.toBooking() } returns booked
            every { booked.complete() } returns completed

            quote.toBooking().complete() shouldBeSameInstanceAs completed

            verify(exactly = 1) {
                quote.toBooking()
                booked.complete()
            }
        }
    }
})
