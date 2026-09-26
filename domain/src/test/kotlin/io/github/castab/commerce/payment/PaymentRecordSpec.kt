package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.fixtures.USD
import io.github.castab.commerce.financial.fixtures.usd
import io.github.castab.commerce.payment.fixtures.RECEIVED_AT
import io.github.castab.commerce.payment.fixtures.payment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class PaymentRecordSpec :
    FunSpec({

        context("amount") {

            test("a positive amount is accepted, and its currency is the payment's currency") {
                val id = UUID.randomUUID()
                val payment = PaymentRecord(id, usd("500.00"), PaymentMethod.CARD, RECEIVED_AT)

                payment.id shouldBe id
                payment.amount shouldBe usd("500.00")
                payment.currency shouldBe USD
                payment.method shouldBe PaymentMethod.CARD
                payment.receivedAt shouldBe RECEIVED_AT
            }

            test("a very small positive amount is accepted") {
                payment(usd("0.01")).amount shouldBe usd("0.01")
            }

            test("zero is rejected, at any scale") {
                listOf("0", "0.00", "0E+3").forEach { zero ->
                    shouldThrow<IllegalArgumentException> { payment(usd(zero)) }.message shouldContain "positive"
                }
            }

            test("a negative amount is rejected") {
                shouldThrow<IllegalArgumentException> { payment(usd("-10.00")) }.message shouldContain "positive"
            }
        }

        context("external reference") {

            test("is optional, for cash, checks, and manually recorded payments") {
                val cash = PaymentRecord(UUID.randomUUID(), usd("40"), PaymentMethod.CASH, RECEIVED_AT)
                cash.externalReference.shouldBeNull()
            }

            test("records a processor transaction without interpreting it") {
                val reference = ExternalPaymentReference(provider = "stripe", reference = "pi_123")
                val card = payment(usd("300"), PaymentMethod.CARD, externalReference = reference)

                card.externalReference shouldBe reference
                card.externalReference?.provider shouldBe "stripe"
                card.externalReference?.reference shouldBe "pi_123"
            }

            test("the provider is independent of the method") {
                val wallet =
                    payment(
                        usd("75"),
                        PaymentMethod.DIGITAL_WALLET,
                        externalReference = ExternalPaymentReference("paypal", "5O190127TN364715T"),
                    )
                wallet.method shouldBe PaymentMethod.DIGITAL_WALLET
                wallet.externalReference?.provider shouldBe "paypal"
            }

            test("a blank provider or reference is rejected") {
                shouldThrow<IllegalArgumentException> { ExternalPaymentReference("", "pi_123") }
                    .message shouldContain "provider"
                shouldThrow<IllegalArgumentException> { ExternalPaymentReference("  ", "pi_123") }
                    .message shouldContain "provider"
                shouldThrow<IllegalArgumentException> { ExternalPaymentReference("stripe", "") }
                    .message shouldContain "reference"
                shouldThrow<IllegalArgumentException> { ExternalPaymentReference("stripe", "\t") }
                    .message shouldContain "reference"
            }

            test("a copy is validated like any other reference") {
                val reference = ExternalPaymentReference("stripe", "pi_123")
                shouldThrow<IllegalArgumentException> { reference.copy(reference = " ") }
            }
        }

        context("methods") {

            test("cover the common instruments, without naming processors") {
                PaymentMethod.entries.map { it.name } shouldBe
                    listOf("CASH", "CHECK", "CARD", "BANK_TRANSFER", "DIGITAL_WALLET", "OTHER")
            }
        }

        context("equality") {

            test("payments are equal when every recorded fact is equal") {
                val id = UUID.randomUUID()
                PaymentRecord(id, usd("10"), PaymentMethod.CASH, RECEIVED_AT) shouldBe
                    PaymentRecord(id, usd("10"), PaymentMethod.CASH, RECEIVED_AT)
                PaymentRecord(id, usd("10"), PaymentMethod.CASH, RECEIVED_AT) shouldNotBe
                    PaymentRecord(id, usd("10"), PaymentMethod.CHECK, RECEIVED_AT)
            }
        }
    })
