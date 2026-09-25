package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.financial.fixtures.USD
import io.github.castab.commerce.financial.fixtures.eur
import io.github.castab.commerce.financial.fixtures.usd
import io.github.castab.commerce.payment.fixtures.invoiceTotalling
import io.github.castab.commerce.payment.fixtures.minutesLater
import io.github.castab.commerce.payment.fixtures.payment
import io.github.castab.commerce.payment.fixtures.quoteTotalling
import io.github.castab.commerce.payment.fixtures.retotal
import io.github.castab.commerce.payment.fixtures.shouldBeNumerically
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class FinancialDocumentReconciliationSpec : FunSpec({

    // D/v2 Quote $1,000 → D/v3 Quote $1,200 → D/v4 Invoice $1,200 → D/v5 Invoice $1,250
    val quoteV1 = quoteTotalling(usd("900.00"))
    val quoteV2 = quoteV1.changeOrder(retotal(quoteV1, usd("1000.00")))
    val quoteV3 = quoteV2.changeOrder(retotal(quoteV2, usd("1200.00")))
    val invoiceV4 = quoteV3.toInvoice()
    val invoiceV5 = invoiceV4.changeOrder(retotal(invoiceV4, usd("1250.00")))

    val deposit = payment(usd("300.00"))
    val finalPayment = payment(usd("1000.00"), PaymentMethod.BANK_TRANSFER)

    test("an allocation to an earlier version counts against the current snapshot's total") {
        val a1 = PaymentAllocation.create(UUID.randomUUID(), deposit, quoteV2, usd("300.00"), minutesLater(1))

        val reconciliation = FinancialDocumentReconciliation.reconcile(invoiceV5, listOf(a1))

        reconciliation.documentReference shouldBe invoiceV5.reference
        reconciliation.currency shouldBe USD
        reconciliation.documentTotal shouldBeNumerically usd("1250.00")
        reconciliation.grossAllocated shouldBeNumerically usd("300.00")
        reconciliation.netApplied shouldBeNumerically usd("300.00")
        reconciliation.balance shouldBeNumerically usd("950.00")
    }

    test("allocations from several versions of the same document aggregate together") {
        val allocations = listOf(
            PaymentAllocation.create(UUID.randomUUID(), deposit, quoteV2, usd("300.00"), minutesLater(1)),
            PaymentAllocation.create(UUID.randomUUID(), finalPayment, invoiceV4, usd("600.00"), minutesLater(2)),
            PaymentAllocation.create(UUID.randomUUID(), finalPayment, invoiceV5, usd("350.00"), minutesLater(3)),
        )

        val reconciliation = FinancialDocumentReconciliation.reconcile(invoiceV5, allocations)

        reconciliation.grossAllocated shouldBeNumerically usd("1250.00")
        reconciliation.balance shouldBeNumerically usd("0")
    }

    test("each allocation keeps the exact version it was made against") {
        val allocations = listOf(
            PaymentAllocation.create(UUID.randomUUID(), deposit, quoteV2, usd("300.00"), minutesLater(1)),
            PaymentAllocation.create(UUID.randomUUID(), finalPayment, invoiceV4, usd("600.00"), minutesLater(2)),
        )

        FinancialDocumentReconciliation.reconcile(invoiceV5, allocations)

        allocations.map { it.financialDocumentReference } shouldContainExactly listOf(
            FinancialDocumentReference(quoteV1.id, Version.of(2)),
            FinancialDocumentReference(quoteV1.id, Version.of(4)),
        )
    }

    test("allocations to another document id are excluded") {
        val otherDocument = invoiceTotalling(usd("5000.00"))
        val allocations = listOf(
            PaymentAllocation.create(UUID.randomUUID(), deposit, quoteV2, usd("300.00"), minutesLater(1)),
            PaymentAllocation.create(UUID.randomUUID(), finalPayment, otherDocument, usd("1000.00"), minutesLater(2)),
        )

        val reconciliation = FinancialDocumentReconciliation.reconcile(invoiceV5, allocations)

        reconciliation.grossAllocated shouldBeNumerically usd("300.00")
        reconciliation.balance shouldBeNumerically usd("950.00")
    }

    test("reversals reduce the net applied amount, and reversals of other documents are ignored") {
        val otherDocument = invoiceTotalling(usd("5000.00"))
        val mine = PaymentAllocation.create(UUID.randomUUID(), finalPayment, invoiceV4, usd("600.00"), minutesLater(1))
        val theirs = PaymentAllocation.create(UUID.randomUUID(), finalPayment, otherDocument, usd("400.00"), minutesLater(1))
        val reversals = listOf(
            PaymentAllocationReversal.create(UUID.randomUUID(), mine, usd("100.00"), minutesLater(2)),
            PaymentAllocationReversal.create(UUID.randomUUID(), theirs, usd("400.00"), minutesLater(2)),
        )

        val reconciliation = FinancialDocumentReconciliation.reconcile(invoiceV5, listOf(mine, theirs), reversals)

        reconciliation.grossAllocated shouldBeNumerically usd("600.00")
        reconciliation.allocationReversals shouldBeNumerically usd("100.00")
        reconciliation.netApplied shouldBeNumerically usd("500.00")
        reconciliation.balance shouldBeNumerically usd("750.00")
    }

    test("refund allocations reduce the net applied amount") {
        val allocation = PaymentAllocation.create(UUID.randomUUID(), deposit, quoteV2, usd("300.00"), minutesLater(1))
        val refund = RefundRecord.create(UUID.randomUUID(), deposit, usd("100.00"), PaymentMethod.CARD, minutesLater(5))
        val unwound = RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("100.00"), minutesLater(5))

        val reconciliation =
            FinancialDocumentReconciliation.reconcile(invoiceV5, listOf(allocation), refundAllocations = listOf(unwound))

        reconciliation.refundAllocations shouldBeNumerically usd("100.00")
        reconciliation.netApplied shouldBeNumerically usd("200.00")
        reconciliation.balance shouldBeNumerically usd("1050.00")
    }

    test("the current snapshot's total is the amount owed") {
        val a1 = PaymentAllocation.create(UUID.randomUUID(), deposit, quoteV2, usd("300.00"), minutesLater(1))

        FinancialDocumentReconciliation.reconcile(quoteV2, listOf(a1)).balance shouldBeNumerically usd("700.00")
        FinancialDocumentReconciliation.reconcile(quoteV3, listOf(a1)).balance shouldBeNumerically usd("900.00")
        FinancialDocumentReconciliation.reconcile(invoiceV5, listOf(a1)).balance shouldBeNumerically usd("950.00")
    }

    test("historical allocations are never rewritten as the document advances") {
        val a1 = PaymentAllocation.create(UUID.randomUUID(), deposit, quoteV2, usd("300.00"), minutesLater(1))
        val before = a1.toString()

        val invoiceV6 = invoiceV5.changeOrder(retotal(invoiceV5, usd("1300.00")))
        FinancialDocumentReconciliation.reconcile(invoiceV6, listOf(a1))

        a1.toString() shouldBe before
        a1.financialDocumentReference shouldBe quoteV2.reference
    }

    test("an over-applied document has a negative balance, left for the application to interpret") {
        val invoice = invoiceTotalling(usd("100.00"))
        val allocation = PaymentAllocation.create(UUID.randomUUID(), deposit, invoice, usd("150.00"), minutesLater(1))

        FinancialDocumentReconciliation.reconcile(invoice, listOf(allocation)).balance shouldBeNumerically usd("-50.00")
    }

    test("a document with nothing applied owes its whole total") {
        val reconciliation = FinancialDocumentReconciliation.reconcile(invoiceV5, emptyList())

        reconciliation.netApplied shouldBeNumerically usd("0")
        reconciliation.balance shouldBeNumerically usd("1250.00")
    }

    test("an allocation to a later version than the reconciled snapshot is rejected") {
        val onV5 = PaymentAllocation.create(UUID.randomUUID(), finalPayment, invoiceV5, usd("100.00"), minutesLater(1))

        shouldThrow<IllegalArgumentException> {
            FinancialDocumentReconciliation.reconcile(invoiceV4, listOf(onV5))
        }.message shouldContain "reconcile the latest snapshot"
    }

    test("an allocation in another currency than the document is rejected") {
        val euroAllocation =
            PaymentAllocation.restore(UUID.randomUUID(), UUID.randomUUID(), quoteV2.reference, eur("10.00"), minutesLater(1))

        shouldThrow<IllegalArgumentException> {
            FinancialDocumentReconciliation.reconcile(invoiceV5, listOf(euroAllocation))
        }.message shouldContain "currencies must match"
    }

    test("a lineage whose currency changed cannot reconcile its earlier allocations") {
        val a1 = PaymentAllocation.create(UUID.randomUUID(), deposit, quoteV2, usd("300.00"), minutesLater(1))
        val euroQuote: FinancialDocument.Quote = quoteV3.changeOrder(retotal(quoteV3, eur("1100.00")))

        shouldThrow<IllegalArgumentException> {
            FinancialDocumentReconciliation.reconcile(euroQuote, listOf(a1))
        }.message shouldContain "currencies must match"
    }

    test("a record supplied twice is rejected rather than counted twice") {
        val a1 = PaymentAllocation.create(UUID.randomUUID(), deposit, quoteV2, usd("300.00"), minutesLater(1))

        shouldThrow<IllegalArgumentException> {
            FinancialDocumentReconciliation.reconcile(invoiceV5, listOf(a1, a1))
        }.message shouldContain "repeat id"
    }
})
