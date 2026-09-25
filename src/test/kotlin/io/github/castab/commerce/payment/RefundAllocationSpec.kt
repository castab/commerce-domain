package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.fixtures.usd
import io.github.castab.commerce.payment.fixtures.invoiceTotalling
import io.github.castab.commerce.payment.fixtures.minutesLater
import io.github.castab.commerce.payment.fixtures.payment
import io.github.castab.commerce.payment.fixtures.shouldBeNumerically
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class RefundAllocationSpec : FunSpec({

    val payment = payment(usd("500.00"))
    val invoice = invoiceTotalling(usd("500.00"))
    val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, invoice, usd("500.00"), minutesLater(1))
    val refund = RefundRecord.create(UUID.randomUUID(), payment, usd("100.00"), PaymentMethod.CARD, minutesLater(60))

    test("unwinds part of a payment allocation, referencing the refund and the allocation by UUID") {
        val id = UUID.randomUUID()
        val refundAllocation = RefundAllocation.create(id, refund, allocation, usd("100.00"), minutesLater(61))

        refundAllocation.id shouldBe id
        refundAllocation.refundReference shouldBe refund.id
        refundAllocation.paymentAllocationReference shouldBe allocation.id
        refundAllocation.amount shouldBe usd("100.00")
        refundAllocation.allocatedAt shouldBe minutesLater(61)

        val reconciliation = PaymentReconciliation.reconcile(
            payment,
            listOf(allocation),
            refunds = listOf(refund),
            refundAllocations = listOf(refundAllocation),
        )
        reconciliation.grossAllocated shouldBeNumerically usd("500.00")
        reconciliation.refundAllocations shouldBeNumerically usd("100.00")
        reconciliation.netAllocated shouldBeNumerically usd("400.00")
        reconciliation.netReceived shouldBeNumerically usd("400.00")
        reconciliation.unallocated shouldBeNumerically usd("0")
    }

    test("a refund may be split into partial refund allocations") {
        val first = RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("60.00"), minutesLater(61))
        val second = RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("40.00"), minutesLater(62))

        PaymentReconciliation.reconcile(
            payment,
            listOf(allocation),
            refunds = listOf(refund),
            refundAllocations = listOf(first, second),
        ).refundAllocations shouldBeNumerically usd("100.00")
    }

    test("the refund and the allocation must belong to the same payment") {
        val otherPayment = payment(usd("500.00"))
        val otherAllocation =
            PaymentAllocation.create(UUID.randomUUID(), otherPayment, invoice, usd("200.00"), minutesLater(2))

        shouldThrow<IllegalArgumentException> {
            RefundAllocation.create(UUID.randomUUID(), refund, otherAllocation, usd("50.00"), minutesLater(61))
        }.message shouldContain "must belong to the same payment"
    }

    test("a cross-payment refund allocation is also rejected during reconciliation") {
        val otherPayment = payment(usd("500.00"))
        val otherAllocation =
            PaymentAllocation.create(UUID.randomUUID(), otherPayment, invoice, usd("200.00"), minutesLater(2))
        val forged = RefundAllocation.restore(UUID.randomUUID(), refund.id, otherAllocation.id, usd("50.00"), minutesLater(61))

        shouldThrow<IllegalArgumentException> {
            PaymentReconciliation.reconcile(
                payment,
                listOf(allocation, otherAllocation),
                refunds = listOf(refund),
                refundAllocations = listOf(forged),
            )
        }.message shouldContain "must belong to the same payment"
        shouldThrow<IllegalArgumentException> {
            PaymentReconciliation.reconcile(
                otherPayment,
                listOf(allocation, otherAllocation),
                refunds = listOf(refund),
                refundAllocations = listOf(forged),
            )
        }.message shouldContain "must belong to the same payment"
    }

    test("a refund allocation whose refund or allocation was not supplied is rejected") {
        val refundAllocation = RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("100.00"), minutesLater(61))

        shouldThrow<IllegalArgumentException> {
            PaymentReconciliation.reconcile(payment, listOf(allocation), refundAllocations = listOf(refundAllocation))
        }.message shouldContain "which was not supplied"
        shouldThrow<IllegalArgumentException> {
            PaymentReconciliation.reconcile(
                payment,
                emptyList(),
                refunds = listOf(refund),
                refundAllocations = listOf(refundAllocation),
            )
        }.message shouldContain "which was not supplied"
    }

    test("a single refund allocation cannot exceed its refund or its allocation") {
        shouldThrow<IllegalArgumentException> {
            RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("100.01"), minutesLater(61))
        }.message shouldContain "exceeds the amount of refund"

        val smallAllocation = PaymentAllocation.create(UUID.randomUUID(), payment, invoice, usd("20.00"), minutesLater(2))
        shouldThrow<IllegalArgumentException> {
            RefundAllocation.create(UUID.randomUUID(), refund, smallAllocation, usd("50.00"), minutesLater(61))
        }.message shouldContain "exceeds the amount of allocation"
    }

    test("a zero or negative refund allocation is rejected") {
        listOf("0.00", "-5").forEach { amount ->
            shouldThrow<IllegalArgumentException> {
                RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd(amount), minutesLater(61))
            }.message shouldContain "positive"
        }
    }

    test("cumulative refund allocations above their refund are rejected during reconciliation") {
        val first = RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("70.00"), minutesLater(61))
        val second = RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("70.00"), minutesLater(62))

        shouldThrow<IllegalArgumentException> {
            PaymentReconciliation.reconcile(
                payment,
                listOf(allocation),
                refunds = listOf(refund),
                refundAllocations = listOf(first, second),
            )
        }.message shouldContain "Refund allocations of refund ${refund.id} total 140.00 USD, which exceeds the refund amount"
    }

    test("reversals and refund allocations together may not reduce an allocation below zero") {
        val bigRefund = RefundRecord.create(UUID.randomUUID(), payment, usd("300.00"), PaymentMethod.CARD, minutesLater(60))
        val reversal = PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("300.00"), minutesLater(30))
        val unwound = RefundAllocation.create(UUID.randomUUID(), bigRefund, allocation, usd("300.00"), minutesLater(61))

        shouldThrow<IllegalArgumentException> {
            PaymentReconciliation.reconcile(
                payment,
                listOf(allocation),
                listOf(reversal),
                listOf(bigRefund),
                listOf(unwound),
            )
        }.message shouldContain "is reduced below zero"
        shouldThrow<IllegalArgumentException> {
            FinancialDocumentReconciliation.reconcile(invoice, listOf(allocation), listOf(reversal), listOf(unwound))
        }.message shouldContain "is reduced below zero"
    }

    test("a refund can exist with no refund allocation, drawing on unapplied money") {
        val partial = PaymentAllocation.create(UUID.randomUUID(), payment, invoice, usd("300.00"), minutesLater(1))

        val reconciliation = PaymentReconciliation.reconcile(payment, listOf(partial), refunds = listOf(refund))

        reconciliation.totalRefunded shouldBeNumerically usd("100.00")
        reconciliation.refundAllocations shouldBeNumerically usd("0")
        reconciliation.netAllocated shouldBeNumerically usd("300.00")
        reconciliation.unallocated shouldBeNumerically usd("100.00")
    }

    test("restore rebuilds an equal refund allocation") {
        val created = RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("10"), minutesLater(61))
        RefundAllocation.restore(
            created.id,
            created.refundReference,
            created.paymentAllocationReference,
            created.amount,
            created.allocatedAt,
        ) shouldBe created
    }
})
