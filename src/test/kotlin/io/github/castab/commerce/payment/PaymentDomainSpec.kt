package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentHistory
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.financial.fixtures.usd
import io.github.castab.commerce.payment.fixtures.estimateTotalling
import io.github.castab.commerce.payment.fixtures.minutesLater
import io.github.castab.commerce.payment.fixtures.payment
import io.github.castab.commerce.payment.fixtures.retotal
import io.github.castab.commerce.payment.fixtures.shouldBeNumerically
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier
import java.util.UUID

/*
 * Intentionally invalid API usage. None of these compile:
 *
 *     PaymentAllocation(id, paymentId, reference, amount, at)   // constructors are private: use create or restore
 *     allocation.amount = usd("1")                              // records are immutable
 *     allocation.copy(amount = usd("1"))                        // records are not data classes
 *     payment.allocations                                       // a payment knows nothing of its allocations
 *     invoice.balance                                           // settlement is derived, never a document field
 */

private val recordTypes = listOf(
    PaymentRecord::class.java,
    PaymentAllocation::class.java,
    PaymentAllocationReversal::class.java,
    RefundRecord::class.java,
    RefundAllocation::class.java,
)

private val reconciliationTypes = listOf(
    PaymentReconciliation::class.java,
    FinancialDocumentReconciliation::class.java,
)

private fun Class<*>.instanceFields() = declaredFields.filterNot { Modifier.isStatic(it.modifiers) }

class PaymentDomainSpec : FunSpec({

    context("the expected end state") {

        test("document history, money received, allocation, correction, and refund stay distinct and auditable") {
            // D/v1 Estimate → D/v2 Quote → D/v3 Quote → D/v4 Invoice ($1,000)
            val estimateV1 = estimateTotalling(usd("900.00"))
            val quoteV2 = estimateV1.toQuote()
            val quoteV3 = quoteV2.changeOrder(retotal(quoteV2, usd("1000.00")))

            // A $300 card deposit through Stripe while D/v2 exists.
            val p1 = payment(
                usd("300.00"),
                PaymentMethod.CARD,
                externalReference = ExternalPaymentReference("stripe", "pi_3Nabc"),
            )
            val a1 = PaymentAllocation.create(UUID.randomUUID(), p1, quoteV2, usd("300.00"), minutesLater(1))

            val invoiceV4 = quoteV3.toInvoice()
            invoiceV4.total shouldBeNumerically usd("1000.00")

            FinancialDocumentReconciliation.reconcile(invoiceV4, listOf(a1)).let {
                it.grossAllocated shouldBeNumerically usd("300.00")
                it.balance shouldBeNumerically usd("700.00")
            }
            a1.financialDocumentReference shouldBe FinancialDocumentReference(invoiceV4.id, Version.of(2))

            // $50 was treated as belonging to this document by mistake, then corrected. No money moved.
            val a2 = PaymentAllocation.restore(UUID.randomUUID(), p1.id, invoiceV4.reference, usd("50.00"), minutesLater(2))
            val r1 = PaymentAllocationReversal.create(UUID.randomUUID(), a2, usd("50.00"), minutesLater(3), "Wrong document")

            // The business genuinely returns $100, in cash, out of the deposit applied by A1.
            val rf1 = RefundRecord.create(UUID.randomUUID(), p1, usd("100.00"), PaymentMethod.CASH, minutesLater(90))
            val ra1 = RefundAllocation.create(UUID.randomUUID(), rf1, a1, usd("100.00"), minutesLater(90))

            val allocations = listOf(a1, a2)
            val reversals = listOf(r1)
            val refunds = listOf(rf1)
            val refundAllocations = listOf(ra1)

            val document = FinancialDocumentReconciliation.reconcile(invoiceV4, allocations, reversals, refundAllocations)
            document.grossAllocated shouldBeNumerically usd("350.00")
            document.allocationReversals shouldBeNumerically usd("50.00")
            document.refundAllocations shouldBeNumerically usd("100.00")
            document.netApplied shouldBeNumerically usd("200.00")
            document.balance shouldBeNumerically usd("800.00")

            val payment = PaymentReconciliation.reconcile(p1, allocations, reversals, refunds, refundAllocations)
            payment.paymentAmount shouldBeNumerically usd("300.00")
            payment.grossAllocated shouldBeNumerically usd("350.00")
            payment.allocationReversals shouldBeNumerically usd("50.00")
            payment.totalRefunded shouldBeNumerically usd("100.00")
            payment.netAllocated shouldBeNumerically usd("200.00")
            payment.unallocated shouldBeNumerically usd("0")

            // Every fact is still there, unchanged.
            p1.amount shouldBe usd("300.00")
            a1.amount shouldBe usd("300.00")
            a1.financialDocumentReference shouldBe quoteV2.reference
            a2.amount shouldBe usd("50.00")
            estimateV1.version shouldBe Version.INITIAL
        }
    }

    context("shape of the payment domain") {

        test("records and reconciliations expose no public constructor where creation checks other records") {
            (recordTypes - PaymentRecord::class.java + reconciliationTypes).forEach { type ->
                // Kotlin's synthetic accessors are invisible to Java and Kotlin source alike.
                type.constructors.filterNot { it.isSynthetic }.shouldBeEmpty()
            }
        }

        test("no record or reconciliation holds mutable state") {
            (recordTypes + reconciliationTypes).forEach { type ->
                type.instanceFields().forEach { field ->
                    Modifier.isFinal(field.modifiers) shouldBe true
                }
                type.methods.filter { it.name.startsWith("set") }.shouldBeEmpty()
            }
        }

        test("records refer to documents, payments, refunds, and allocations only by reference") {
            val embeddable = setOf(
                FinancialDocument::class.java,
                PaymentRecord::class.java,
                RefundRecord::class.java,
                PaymentAllocation::class.java,
                PaymentAllocationReversal::class.java,
                RefundAllocation::class.java,
            )
            (recordTypes + reconciliationTypes).forEach { type ->
                type.instanceFields().filter { field -> embeddable.any { it.isAssignableFrom(field.type) } }
                    .shouldBeEmpty()
                type.instanceFields().filter { Collection::class.java.isAssignableFrom(it.type) }.shouldBeEmpty()
            }
        }

        test("the financial domain knows nothing of payments") {
            val financialTypes = listOf(
                FinancialDocument::class.java,
                FinancialDocument.Estimate::class.java,
                FinancialDocument.Quote::class.java,
                FinancialDocument.Invoice::class.java,
                FinancialDocumentReference::class.java,
                FinancialDocumentHistory::class.java,
                Money::class.java,
                LineItem::class.java,
                ChangeOrder::class.java,
                Version::class.java,
            )
            val paymentPackage = PaymentRecord::class.java.packageName
            financialTypes.forEach { type ->
                val mentioned = type.declaredFields.map { it.type } +
                    type.declaredMethods.flatMap { it.parameterTypes.toList() + it.returnType }
                mentioned.filter { it.packageName == paymentPackage }.shouldBeEmpty()
            }
        }
    }
})
