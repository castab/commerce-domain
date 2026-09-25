package io.github.castab.commerce.customer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CustomerSpec : FunSpec({
    test("customer retains only identity and contact values") {
        val uuid = UUID.randomUUID()
        val customer = Customer(CustomerId(uuid), CustomerName("Bob Smith"), EmailAddress("bob@example.com"), PhoneNumber("+44 20 7946 0958"))

        customer.id.value shouldBe uuid
        customer.name.value shouldBe "Bob Smith"
        customer.email.value shouldBe "bob@example.com"
        customer.phoneNumber.value shouldBe "+44 20 7946 0958"
        Customer::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet() shouldBe
            setOf("id", "name", "email", "phoneNumber")
    }

    test("required text values reject blanks, including through copy") {
        shouldThrow<IllegalArgumentException> { CustomerName("  ") }
        shouldThrow<IllegalArgumentException> { EmailAddress("\t") }
        shouldThrow<IllegalArgumentException> { PhoneNumber("") }
        shouldThrow<IllegalArgumentException> { CustomerName("Bob").copy(value = " ") }
    }
})
