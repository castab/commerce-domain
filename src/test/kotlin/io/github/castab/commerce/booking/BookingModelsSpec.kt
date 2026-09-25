package io.github.castab.commerce.booking

import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.customer.CustomerId
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.customer.PhoneNumber
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BookingModelsSpec :
    FunSpec({
        val customerId = CustomerId(UUID.randomUUID())
        val customer = Customer(customerId, CustomerName("Bob Smith"), EmailAddress("bob@example.com"), PhoneNumber("559-555-1111"))
        val first = Booking(BookingId(UUID.randomUUID()), customer.id)
        val second = Booking(BookingId(UUID.randomUUID()), customer.id)

        test("separate bookings reference one customer by identity without embedding it") {
            first.customerId shouldBe second.customerId
            (first.id != second.id) shouldBe true
            Booking::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet() shouldBe
                setOf("id", "customerId")
        }

        test("customer-backed contact references the customer without duplicating their details") {
            val contact = BookingContact.CustomerContact(BookingContactId(UUID.randomUUID()), first.id, customer.id)
            contact.bookingId shouldBe first.id
            contact.customerId shouldBe customer.id
            BookingContact.CustomerContact::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet() shouldBe
                setOf("id", "bookingId", "customerId")
        }

        test("multiple independently identified contacts may serve one booking") {
            val known = BookingContact.CustomerContact(BookingContactId(UUID.randomUUID()), first.id, customer.id)
            val other =
                BookingContact.ExternalContact(
                    BookingContactId(UUID.randomUUID()),
                    first.id,
                    CustomerName("Alice Smith"),
                    email = EmailAddress("alice@example.com"),
                )
            val dayOf =
                BookingContact.ExternalContact(
                    BookingContactId(UUID.randomUUID()),
                    first.id,
                    CustomerName("Venue representative"),
                    phoneNumber = PhoneNumber("+1 559 555 2222"),
                )
            listOf(known, other, dayOf).map { it.bookingId }.toSet() shouldBe setOf(first.id)
            listOf(known, other, dayOf).map { it.id }.toSet().size shouldBe 3
            BookingContact.ExternalContact::class.java.declaredFields
                .map { it.name }
                .filterNot {
                    it.startsWith(
                        "$",
                    )
                }.contains("customerId") shouldBe
                false
        }

        test("external contact requires a name and at least one contact method") {
            shouldThrow<IllegalArgumentException> {
                BookingContact.ExternalContact(BookingContactId(UUID.randomUUID()), first.id, CustomerName("Alice"))
            }
            shouldThrow<IllegalArgumentException> { CustomerName(" ") }
            shouldThrow<IllegalArgumentException> { EmailAddress(" ") }
            shouldThrow<IllegalArgumentException> { PhoneNumber(" ") }
            val valid =
                BookingContact.ExternalContact(
                    BookingContactId(UUID.randomUUID()),
                    first.id,
                    CustomerName("Alice"),
                    phoneNumber = PhoneNumber("555-2222"),
                )
            shouldThrow<IllegalArgumentException> { valid.copy(email = null, phoneNumber = null) }
        }

        test("locations belong to bookings, and the same customer may have different addresses") {
            val firstLocation =
                BookingLocation(
                    BookingLocationId(UUID.randomUUID()),
                    first.id,
                    PostalAddress("123 Main St", "Suite 2", "Fresno", "CA", "93721", "US"),
                )
            val secondLocation =
                BookingLocation(
                    BookingLocationId(UUID.randomUUID()),
                    second.id,
                    PostalAddress("5 High Street", city = "London", region = "Greater London", postalCode = "SW1A 1AA", countryCode = "GB"),
                )
            firstLocation.address.postalCode shouldBe "93721"
            secondLocation.address.postalCode shouldBe "SW1A 1AA"
            (firstLocation.address != secondLocation.address) shouldBe true
            BookingLocation::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet() shouldBe
                setOf("id", "bookingId", "address")
        }

        test("address fields reject blanks and operational records can be discarded independently") {
            shouldThrow<IllegalArgumentException> { PostalAddress(" ", city = "Fresno", region = "CA", postalCode = "93721") }
            shouldThrow<IllegalArgumentException> { PostalAddress("123 Main", city = " ", region = "CA", postalCode = "93721") }
            shouldThrow<IllegalArgumentException> { PostalAddress("123 Main", city = "Fresno", region = " ", postalCode = "93721") }
            shouldThrow<IllegalArgumentException> { PostalAddress("123 Main", city = "Fresno", region = "CA", postalCode = " ") }
            PostalAddress("No Postcode Road", city = "Example City").postalCode shouldBe null
            val contacts =
                mutableListOf<BookingContact>(BookingContact.CustomerContact(BookingContactId(UUID.randomUUID()), first.id, customer.id))
            val locations =
                mutableListOf(
                    BookingLocation(
                        BookingLocationId(UUID.randomUUID()),
                        first.id,
                        PostalAddress("123 Main", city = "Fresno", region = "CA", postalCode = "93721"),
                    ),
                )
            contacts.clear()
            locations.clear()
            contacts.size shouldBe 0
            locations.size shouldBe 0
            first.customerId shouldBe customer.id
            customer.name.value shouldBe "Bob Smith"
        }
    })
