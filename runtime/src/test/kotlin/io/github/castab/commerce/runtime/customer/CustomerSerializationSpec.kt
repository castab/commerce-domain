package io.github.castab.commerce.runtime.customer

import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.runtime.http.CommerceJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CustomerSerializationSpec :
    FunSpec({
        test("a create request reads its fields and ignores unknown ones") {
            CommerceJson.asA(
                """{"name":"Ada Lovelace","email":"ada@example.com","phone":"+1 555 0100"}""",
                CreateCustomerRequest.serializer(),
            ) shouldBe CreateCustomerRequest("Ada Lovelace", "ada@example.com")
        }

        test("a customer response is translated explicitly from the domain customer") {
            val id = UUID.fromString("5f0c6a7e-8f8e-4d7a-9a55-1f8f0b5d2c11")
            val customer = Customer(Customer.Id(id), CustomerName("Ada Lovelace"), EmailAddress("ada@example.com"))

            CommerceJson.asFormatString(CustomerResponse.serializer(), CustomerResponse.from(customer)) shouldBe
                """{"id":"5f0c6a7e-8f8e-4d7a-9a55-1f8f0b5d2c11","name":"Ada Lovelace","email":"ada@example.com"}"""
        }
    })
