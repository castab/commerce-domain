package io.github.castab.commerce.customer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CustomerSpec :
    FunSpec({
        test("customer retains durable identity without a phone number") {
            val uuid = UUID.randomUUID()
            val customer =
                Customer(Customer.Id(uuid), CustomerName("Bob Smith"), EmailAddress("bob@example.com"))

            customer.id.value shouldBe uuid
            Customer::class.java.getDeclaredField("id").type shouldBe Customer.Id::class.java
            customer.name.value shouldBe "Bob Smith"
            customer.email.value shouldBe "bob@example.com"
            Customer::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet() shouldBe
                setOf("id", "name", "email")
        }

        test("required text values reject blanks, including through copy") {
            shouldThrow<IllegalArgumentException> { CustomerName("  ") }
            shouldThrow<IllegalArgumentException> { EmailAddress("\t") }
            shouldThrow<IllegalArgumentException> { PhoneNumber("") }
            shouldThrow<IllegalArgumentException> { CustomerName("Bob").copy(value = " ") }
        }
    })
