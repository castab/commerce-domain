package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.fixtures.eur
import io.github.castab.commerce.financial.fixtures.usd
import io.github.castab.commerce.payment.fixtures.invoiceTotalling
import io.github.castab.commerce.payment.fixtures.minutesLater
import io.github.castab.commerce.payment.fixtures.payment
import io.github.castab.commerce.payment.fixtures.shouldBeNumerically
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class PaymentAllocationReversalSpec : FunSpec({

    val payment = payment(usd("500.00"))
    val invoice = invoiceTotalling(usd("500.00"))
    val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, invoice, usd("500.00"), minutesLater(1))

    test("a full reversal is supported and references the allocation by its UUID") {
        val id = UUID.randomUUID()
        val reversal = PaymentAllocationReversal.create(id, allocation, usd("500.00"), minutesLater(2), "Wrong invoice")

        reversal.id shouldBe id
        reversal.paymentAllocationReference shouldBe allocation.id
        reversal.amount shouldBe usd("500.00")
        reversal.reversedAt shouldBe minutesLater(2)
        reversal.reason shouldBe "Wrong invoice"
    }

    test("a partial reversal is supported") {
        val reversal = PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("200.00"), minutesLater(2))

        reversal.amount shouldBe usd("200.00")
        reversal.reason.shouldBeNull()
    }

    test("a zero or negative reversal is rejected") {
        listOf("0", "0.00", "-50.00").forEach { amount ->
            shouldThrow<IllegalArgumentException> {
                PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd(amount), minutesLater(2))
            }.message shouldContain "positive"
        }
    }

    test("a single reversal larger than its allocation is rejected") {
        shouldThrow<IllegalArgumentException> {
            PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("500.01"), minutesLater(2))
        }.message shouldContain "exceeds the amount of allocation"
    }

    test("a reversal in another currency than its allocation is rejected") {
        shouldThrow<IllegalArgumentException> {
            PaymentAllocationReversal.create(UUID.randomUUID(), allocation, eur("100.00"), minutesLater(2))
        }.message shouldContain "currencies must match"
    }

    test("a blank reason is rejected; no reason at all is allowed") {
        shouldThrow<IllegalArgumentException> {
            PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("10"), minutesLater(2), reason = "  ")
        }.message shouldContain "reason"
        shouldThrow<IllegalArgumentException> {
            PaymentAllocationReversal.restore(UUID.randomUUID(), allocation.id, usd("10"), minutesLater(2), reason = "")
        }.message shouldContain "reason"
    }

    test("cumulative reversals larger than the allocation are rejected during reconciliation") {
        val first = PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("300.00"), minutesLater(2))
        val second = PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("300.00"), minutesLater(3))

        shouldThrow<IllegalArgumentException> {
            PaymentReconciliation.reconcile(payment, listOf(allocation), listOf(first, second))
        }.message shouldContain "Reversals of payment allocation ${allocation.id} total 600.00 USD"
        shouldThrow<IllegalArgumentException> {
            FinancialDocumentReconciliation.reconcile(invoice, listOf(allocation), listOf(first, second))
        }.message shouldContain "Reversals of payment allocation ${allocation.id}"
    }

    test("a reversal changes neither the allocation nor the payment") {
        val paymentBefore = PaymentRecord(payment.id, payment.amount, payment.method, payment.receivedAt)
        val allocationBefore = PaymentAllocation.restore(
            allocation.id,
            allocation.paymentReference,
            allocation.financialDocumentReference,
            allocation.amount,
            allocation.allocatedAt,
        )

        PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("200.00"), minutesLater(2))

        payment shouldBe paymentBefore
        allocation shouldBe allocationBefore
    }

    test("a reversal neither creates nor implies a refund: the value becomes unallocated, not refunded") {
        val reversal = PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("200.00"), minutesLater(2))

        val reconciliation = PaymentReconciliation.reconcile(payment, listOf(allocation), listOf(reversal))

        reconciliation.totalRefunded shouldBeNumerically usd("0")
        reconciliation.netReceived shouldBeNumerically usd("500.00")
        reconciliation.allocationReversals shouldBeNumerically usd("200.00")
        reconciliation.netAllocated shouldBeNumerically usd("300.00")
        reconciliation.unallocated shouldBeNumerically usd("200.00")
        PaymentAllocationReversal::class.java.declaredFields.none { it.type == RefundRecord::class.java } shouldBe true
    }

    test("restore rebuilds an equal reversal") {
        val created = PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("5"), minutesLater(2), "Typo")
        PaymentAllocationReversal.restore(
            created.id,
            created.paymentAllocationReference,
            created.amount,
            created.reversedAt,
            created.reason,
        ) shouldBe created
    }
})
