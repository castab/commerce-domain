package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.financial.fixtures.USD
import io.github.castab.commerce.financial.fixtures.eur
import io.github.castab.commerce.financial.fixtures.usd
import io.github.castab.commerce.payment.fixtures.estimateTotalling
import io.github.castab.commerce.payment.fixtures.invoiceTotalling
import io.github.castab.commerce.payment.fixtures.minutesLater
import io.github.castab.commerce.payment.fixtures.payment
import io.github.castab.commerce.payment.fixtures.quoteTotalling
import io.github.castab.commerce.payment.fixtures.retotal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class PaymentAllocationSpec :
    FunSpec({

        val payment = payment(usd("500.00"))

        context("references") {

            test("references the payment by its UUID and the exact document snapshot") {
                val quoteV1 = quoteTotalling(usd("900.00"))
                val quote = quoteV1.changeOrder(retotal(quoteV1, usd("1000.00")))
                val id = UUID.randomUUID()

                val allocation = PaymentAllocation.create(id, payment, quote, usd("300.00"), minutesLater(5))

                allocation.id shouldBe id
                allocation.paymentReference shouldBe payment.id
                allocation.financialDocumentReference shouldBe quote.reference
                allocation.financialDocumentReference shouldBe FinancialDocumentReference(quote.id, Version.of(2))
                allocation.amount shouldBe usd("300.00")
                allocation.currency shouldBe USD
                allocation.allocatedAt shouldBe minutesLater(5)
            }

            test("preserves the document version when the document later advances") {
                val quoteV1 = quoteTotalling(usd("1000.00"))
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, quoteV1, usd("300.00"), minutesLater(1))

                val quoteV2 = quoteV1.changeOrder(retotal(quoteV1, usd("1200.00")))
                val invoiceV3 = quoteV2.toInvoice()

                invoiceV3.version shouldBe Version.of(3)
                allocation.financialDocumentReference shouldBe FinancialDocumentReference(quoteV1.id, Version.INITIAL)
                allocation.financialDocumentReference.id shouldBe invoiceV3.id
            }

            test("exact-snapshot and lineage queries are both plain comparisons") {
                val quoteV1 = quoteTotalling(usd("1000.00"))
                val quoteV2 = quoteV1.changeOrder(retotal(quoteV1, usd("1100.00")))
                val other = quoteTotalling(usd("50.00"))
                val allocations =
                    listOf(
                        PaymentAllocation.create(UUID.randomUUID(), payment, quoteV1, usd("100"), minutesLater(1)),
                        PaymentAllocation.create(UUID.randomUUID(), payment, quoteV2, usd("200"), minutesLater(2)),
                        PaymentAllocation.create(UUID.randomUUID(), payment, other, usd("50"), minutesLater(3)),
                    )

                allocations
                    .filter { it.financialDocumentReference == quoteV2.reference }
                    .map { it.amount } shouldContainExactly listOf(usd("200"))
                allocations
                    .filter { it.financialDocumentReference.id == quoteV2.id }
                    .map { it.amount } shouldContainExactly listOf(usd("100"), usd("200"))
            }

            test("retains neither the payment nor the document") {
                PaymentAllocation::class.java.declaredFields
                    .map { it.type.simpleName }
                    .toSet() shouldBe
                    setOf("UUID", "FinancialDocumentReference", "Money", "Instant", "Companion")
            }
        }

        context("currency") {

            test("matching payment and document currencies succeed") {
                val invoice = invoiceTotalling(usd("500.00"))
                PaymentAllocation
                    .create(UUID.randomUUID(), payment, invoice, usd("500.00"), minutesLater(1))
                    .currency shouldBe USD
            }

            test("a document in another currency than the payment is rejected") {
                val euroInvoice = invoiceTotalling(eur("500.00"))
                shouldThrow<IllegalArgumentException> {
                    PaymentAllocation.create(UUID.randomUUID(), payment, euroInvoice, usd("100.00"), minutesLater(1))
                }.message shouldContain "currencies must match"
            }

            test("an amount in another currency than the payment is rejected") {
                val invoice = invoiceTotalling(usd("500.00"))
                shouldThrow<IllegalArgumentException> {
                    PaymentAllocation.create(UUID.randomUUID(), payment, invoice, eur("100.00"), minutesLater(1))
                }.message shouldContain "currencies must match"
            }
        }

        context("amount") {

            val invoice = invoiceTotalling(usd("800.00"))

            test("must be strictly positive") {
                listOf("0", "0.00", "-1.00").forEach { amount ->
                    shouldThrow<IllegalArgumentException> {
                        PaymentAllocation.create(UUID.randomUUID(), payment, invoice, usd(amount), minutesLater(1))
                    }.message shouldContain "positive"
                    shouldThrow<IllegalArgumentException> {
                        PaymentAllocation.restore(UUID.randomUUID(), payment.id, invoice.reference, usd(amount), minutesLater(1))
                    }.message shouldContain "positive"
                }
            }

            test("a single allocation cannot exceed its payment") {
                shouldThrow<IllegalArgumentException> {
                    PaymentAllocation.create(UUID.randomUUID(), payment, invoice, usd("500.01"), minutesLater(1))
                }.message shouldContain "exceeds the amount of payment"
            }

            test("the full payment amount may be allocated, compared numerically") {
                PaymentAllocation
                    .create(UUID.randomUUID(), payment, invoice, usd("500"), minutesLater(1))
                    .amount shouldBe usd("500")
            }
        }

        context("stages") {

            test("estimates, quotes, and invoices are all accepted; which may take money is application policy") {
                listOf(estimateTotalling(usd("100")), quoteTotalling(usd("100")), invoiceTotalling(usd("100")))
                    .forEach { document ->
                        val allocation =
                            PaymentAllocation.create(UUID.randomUUID(), payment, document, usd("100"), minutesLater(1))
                        allocation.financialDocumentReference shouldBe document.reference
                    }
            }
        }

        context("multiplicity") {

            test("one payment can have several allocations, to different documents") {
                val first = invoiceTotalling(usd("200"))
                val second = quoteTotalling(usd("400"))

                val a1 = PaymentAllocation.create(UUID.randomUUID(), payment, first, usd("200"), minutesLater(1))
                val a2 = PaymentAllocation.create(UUID.randomUUID(), payment, second, usd("300"), minutesLater(2))

                a1.paymentReference shouldBe a2.paymentReference
                a1.financialDocumentReference.id shouldNotBe a2.financialDocumentReference.id
            }

            test("several payments can be allocated to the same document lineage") {
                val invoice = invoiceTotalling(usd("900"))
                val other = payment(usd("400"), PaymentMethod.CHECK)

                val a1 = PaymentAllocation.create(UUID.randomUUID(), payment, invoice, usd("500"), minutesLater(1))
                val a2 = PaymentAllocation.create(UUID.randomUUID(), other, invoice, usd("400"), minutesLater(2))

                a1.financialDocumentReference shouldBe a2.financialDocumentReference
                a1.paymentReference shouldNotBe a2.paymentReference
            }
        }

        context("restoration") {

            test("restore rebuilds an equal allocation from references alone") {
                val invoice = invoiceTotalling(usd("500"))
                val created = PaymentAllocation.create(UUID.randomUUID(), payment, invoice, usd("250.00"), minutesLater(1))

                val restored =
                    PaymentAllocation.restore(
                        id = created.id,
                        paymentReference = created.paymentReference,
                        financialDocumentReference = created.financialDocumentReference,
                        amount = created.amount,
                        allocatedAt = created.allocatedAt,
                    )

                restored shouldBe created
                restored.hashCode() shouldBe created.hashCode()
            }
        }
    })
