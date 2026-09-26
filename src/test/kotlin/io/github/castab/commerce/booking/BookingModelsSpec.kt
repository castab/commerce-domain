package io.github.castab.commerce.booking

import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.customer.PhoneNumber
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BookingModelsSpec :
    FunSpec({
        val customerId = Customer.Id(UUID.randomUUID())
        val customer = Customer(customerId, CustomerName("Bob Smith"), EmailAddress("bob@example.com"))
        val first = Booking(Booking.Id(UUID.randomUUID()), customer.id)
        val second = Booking(Booking.Id(UUID.randomUUID()), customer.id)

        test("booking record IDs are scoped to their owning models") {
            Booking::class.java.getDeclaredField("id").type shouldBe Booking.Id::class.java
            BookingContact.CustomerContact::class.java.getDeclaredField("id").type shouldBe BookingContact.Id::class.java
            BookingContact.CustomerContact::class.java.getDeclaredField("bookingId").type shouldBe Booking.Id::class.java
            BookingLocation::class.java.getDeclaredField("id").type shouldBe BookingLocation.Id::class.java
            BookingLocation::class.java.getDeclaredField("bookingId").type shouldBe Booking.Id::class.java
        }

        test("bookings can exist without operational contacts and share a customer identity") {
            first.customerId shouldBe second.customerId
            (first.id != second.id) shouldBe true
            Booking::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet() shouldBe
                setOf("id", "customerId")
        }

        test("customer-backed contact resolves name and email through the customer without requiring a phone") {
            val contact = BookingContact.CustomerContact(BookingContact.Id(UUID.randomUUID()), first.id, customer.id)
            contact.bookingId shouldBe first.id
            contact.customerId shouldBe customer.id
            contact.phoneNumber shouldBe null
            customer.name.value shouldBe "Bob Smith"
            customer.email.value shouldBe "bob@example.com"
            BookingContact.CustomerContact::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet() shouldBe
                setOf("id", "bookingId", "customerId", "phoneNumber")
        }

        test("customer-backed contacts can hold different booking-scoped phone numbers") {
            val firstContact =
                BookingContact.CustomerContact(
                    BookingContact.Id(UUID.randomUUID()),
                    first.id,
                    customer.id,
                    phoneNumber = PhoneNumber("559-555-1111"),
                )
            val secondContact =
                BookingContact.CustomerContact(
                    BookingContact.Id(UUID.randomUUID()),
                    second.id,
                    customer.id,
                    phoneNumber = PhoneNumber("559-555-3333"),
                )

            firstContact.phoneNumber?.value shouldBe "559-555-1111"
            secondContact.phoneNumber?.value shouldBe "559-555-3333"
            firstContact.customerId shouldBe secondContact.customerId
            firstContact.copy(phoneNumber = null).phoneNumber shouldBe null
        }

        test("multiple independently identified contacts may serve one booking") {
            val known = BookingContact.CustomerContact(BookingContact.Id(UUID.randomUUID()), first.id, customer.id)
            val other =
                BookingContact.ExternalContact(
                    BookingContact.Id(UUID.randomUUID()),
                    first.id,
                    CustomerName("Alice Smith"),
                    email = EmailAddress("alice@example.com"),
                )
            val dayOf =
                BookingContact.ExternalContact(
                    BookingContact.Id(UUID.randomUUID()),
                    first.id,
                    CustomerName("Venue representative"),
                    phoneNumber = PhoneNumber("+1 559 555 2222"),
                )
            val coordinator =
                BookingContact.ExternalContact(
                    BookingContact.Id(UUID.randomUUID()),
                    first.id,
                    CustomerName("Sarah Jones"),
                    email = EmailAddress("sarah@example.com"),
                    phoneNumber = PhoneNumber("+1 559 555 4444"),
                )
            other.name.value shouldBe "Alice Smith"
            other.email?.value shouldBe "alice@example.com"
            other.phoneNumber shouldBe null
            dayOf.email shouldBe null
            dayOf.phoneNumber?.value shouldBe "+1 559 555 2222"
            coordinator.name shouldBe CustomerName("Sarah Jones")
            coordinator.email shouldBe EmailAddress("sarah@example.com")
            coordinator.phoneNumber shouldBe PhoneNumber("+1 559 555 4444")
            listOf(known, other, dayOf, coordinator).map { it.bookingId }.toSet() shouldBe setOf(first.id)
            listOf(known, other, dayOf, coordinator).map { it.id }.toSet().size shouldBe 4
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
                BookingContact.ExternalContact(BookingContact.Id(UUID.randomUUID()), first.id, CustomerName("Alice"))
            }
            shouldThrow<IllegalArgumentException> { CustomerName(" ") }
            shouldThrow<IllegalArgumentException> { EmailAddress(" ") }
            shouldThrow<IllegalArgumentException> { PhoneNumber(" ") }
            val valid =
                BookingContact.ExternalContact(
                    BookingContact.Id(UUID.randomUUID()),
                    first.id,
                    CustomerName("Alice"),
                    phoneNumber = PhoneNumber("555-2222"),
                )
            shouldThrow<IllegalArgumentException> { valid.copy(email = null, phoneNumber = null) }
        }

        test("locations belong to bookings, and the same customer may have different addresses") {
            val firstLocation =
                BookingLocation(
                    BookingLocation.Id(UUID.randomUUID()),
                    first.id,
                    PostalAddress("123 Main St", "Suite 2", "Fresno", "CA", "93721", "US"),
                )
            val secondLocation =
                BookingLocation(
                    BookingLocation.Id(UUID.randomUUID()),
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
                mutableListOf<BookingContact>(BookingContact.CustomerContact(BookingContact.Id(UUID.randomUUID()), first.id, customer.id))
            val locations =
                mutableListOf(
                    BookingLocation(
                        BookingLocation.Id(UUID.randomUUID()),
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
