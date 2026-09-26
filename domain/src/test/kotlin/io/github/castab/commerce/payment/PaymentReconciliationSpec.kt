package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.fixtures.USD
import io.github.castab.commerce.financial.fixtures.eur
import io.github.castab.commerce.financial.fixtures.usd
import io.github.castab.commerce.payment.fixtures.invoiceTotalling
import io.github.castab.commerce.payment.fixtures.minutesLater
import io.github.castab.commerce.payment.fixtures.payment
import io.github.castab.commerce.payment.fixtures.quoteTotalling
import io.github.castab.commerce.payment.fixtures.shouldBeNumerically
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class PaymentReconciliationSpec :
    FunSpec({

        val documentX = invoiceTotalling(usd("500.00"))
        val documentY = quoteTotalling(usd("800.00"))

        context("payment-level derivation") {

            test("an unapplied payment is entirely unallocated") {
                val payment = payment(usd("500.00"))

                val reconciliation = PaymentReconciliation.reconcile(payment, emptyList())

                reconciliation.paymentReference shouldBe payment.id
                reconciliation.currency shouldBe USD
                reconciliation.paymentAmount shouldBe usd("500.00")
                reconciliation.totalRefunded shouldBeNumerically usd("0")
                reconciliation.grossAllocated shouldBeNumerically usd("0")
                reconciliation.allocationReversals shouldBeNumerically usd("0")
                reconciliation.refundAllocations shouldBeNumerically usd("0")
                reconciliation.netAllocated shouldBeNumerically usd("0")
                reconciliation.netReceived shouldBeNumerically usd("500.00")
                reconciliation.unallocated shouldBeNumerically usd("500.00")
            }

            test("a partially applied payment keeps the rest unallocated") {
                val payment = payment(usd("500.00"))
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("125.50"), minutesLater(1))

                val reconciliation = PaymentReconciliation.reconcile(payment, listOf(allocation))

                reconciliation.grossAllocated shouldBeNumerically usd("125.50")
                reconciliation.unallocated shouldBeNumerically usd("374.50")
            }

            test("a reversed allocation makes its value available again") {
                // P1 $500; A1 $500 → X; R1 $200 reverses A1; A2 $200 → Y.
                val payment = payment(usd("500.00"))
                val a1 = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("500.00"), minutesLater(1))
                val r1 = PaymentAllocationReversal.create(UUID.randomUUID(), a1, usd("200.00"), minutesLater(2), "Fat finger")
                val a2 = PaymentAllocation.create(UUID.randomUUID(), payment, documentY, usd("200.00"), minutesLater(3))

                val reconciliation = PaymentReconciliation.reconcile(payment, listOf(a1, a2), listOf(r1))

                reconciliation.grossAllocated shouldBeNumerically usd("700.00")
                reconciliation.allocationReversals shouldBeNumerically usd("200.00")
                reconciliation.netAllocated shouldBeNumerically usd("500.00")
                reconciliation.totalRefunded shouldBeNumerically usd("0")
                reconciliation.unallocated shouldBeNumerically usd("0")
            }

            test("a reversal alone leaves its value unallocated") {
                val payment = payment(usd("500.00"))
                val a1 = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("500.00"), minutesLater(1))
                val r1 = PaymentAllocationReversal.create(UUID.randomUUID(), a1, usd("200.00"), minutesLater(2))

                val reconciliation = PaymentReconciliation.reconcile(payment, listOf(a1), listOf(r1))

                reconciliation.netAllocated shouldBeNumerically usd("300.00")
                reconciliation.unallocated shouldBeNumerically usd("200.00")
            }

            test("a refund from unapplied money reduces what is kept, not what is applied") {
                // Payment $500; allocation $300; refund $100 from the unapplied $200.
                val payment = payment(usd("500.00"))
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("300.00"), minutesLater(1))
                val refund = RefundRecord.create(UUID.randomUUID(), payment, usd("100.00"), PaymentMethod.CASH, minutesLater(9))

                val reconciliation = PaymentReconciliation.reconcile(payment, listOf(allocation), refunds = listOf(refund))

                reconciliation.netAllocated shouldBeNumerically usd("300.00")
                reconciliation.netReceived shouldBeNumerically usd("400.00")
                reconciliation.unallocated shouldBeNumerically usd("100.00")
            }

            test("a refund against an allocated amount unwinds it through a refund allocation") {
                // Payment $500; allocation $500; refund $100; refund allocation $100.
                val payment = payment(usd("500.00"))
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("500.00"), minutesLater(1))
                val refund = RefundRecord.create(UUID.randomUUID(), payment, usd("100.00"), PaymentMethod.CARD, minutesLater(9))
                val unwound = RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("100.00"), minutesLater(9))

                val reconciliation =
                    PaymentReconciliation.reconcile(
                        payment,
                        listOf(allocation),
                        refunds = listOf(refund),
                        refundAllocations = listOf(unwound),
                    )

                reconciliation.grossAllocated shouldBeNumerically usd("500.00")
                reconciliation.refundAllocations shouldBeNumerically usd("100.00")
                reconciliation.netAllocated shouldBeNumerically usd("400.00")
                reconciliation.netReceived shouldBeNumerically usd("400.00")
                reconciliation.unallocated shouldBeNumerically usd("0")
            }

            test("a reversal makes value allocatable again; a refund with a refund allocation does not") {
                // From the same fully allocated $500 payment, $100 is either reversed or refunded.
                val payment = payment(usd("500.00"))
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("500.00"), minutesLater(1))
                val before = PaymentReconciliation.reconcile(payment, listOf(allocation))
                before.unallocated shouldBeNumerically usd("0")

                val reversal = PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("100.00"), minutesLater(2))
                val reversed = PaymentReconciliation.reconcile(payment, listOf(allocation), listOf(reversal))

                reversed.netReceived shouldBeNumerically usd("500.00")
                reversed.netAllocated shouldBeNumerically usd("400.00")
                reversed.unallocated shouldBeNumerically usd("100.00")

                val refund = RefundRecord.create(UUID.randomUUID(), payment, usd("100.00"), PaymentMethod.CARD, minutesLater(2))
                val unwound = RefundAllocation.create(UUID.randomUUID(), refund, allocation, usd("100.00"), minutesLater(2))
                val refunded =
                    PaymentReconciliation.reconcile(
                        payment,
                        listOf(allocation),
                        refunds = listOf(refund),
                        refundAllocations = listOf(unwound),
                    )

                refunded.netReceived shouldBeNumerically usd("400.00")
                refunded.netAllocated shouldBeNumerically usd("400.00")
                refunded.unallocated shouldBeNumerically before.unallocated

                // The refunded $100 cannot be allocated again, while the reversed $100 can.
                val reallocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentY, usd("100.00"), minutesLater(3))
                PaymentReconciliation
                    .reconcile(payment, listOf(allocation, reallocation), listOf(reversal))
                    .unallocated shouldBeNumerically usd("0")
                shouldThrow<IllegalArgumentException> {
                    PaymentReconciliation.reconcile(
                        payment,
                        listOf(allocation, reallocation),
                        refunds = listOf(refund),
                        refundAllocations = listOf(unwound),
                    )
                }.message shouldContain "is over-applied"
            }

            test("a refund of allocated money without a refund allocation is rejected as over-applied") {
                val payment = payment(usd("500.00"))
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("500.00"), minutesLater(1))
                val refund = RefundRecord.create(UUID.randomUUID(), payment, usd("100.00"), PaymentMethod.CARD, minutesLater(9))

                shouldThrow<IllegalArgumentException> {
                    PaymentReconciliation.reconcile(payment, listOf(allocation), refunds = listOf(refund))
                }.message shouldContain "is over-applied"
            }

            test("a payment split across several documents counts every allocation") {
                val payment = payment(usd("500.00"))
                val allocations =
                    listOf(
                        PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("200.00"), minutesLater(1)),
                        PaymentAllocation.create(UUID.randomUUID(), payment, documentY, usd("250.00"), minutesLater(2)),
                    )

                val reconciliation = PaymentReconciliation.reconcile(payment, allocations)

                reconciliation.grossAllocated shouldBeNumerically usd("450.00")
                reconciliation.netAllocated shouldBeNumerically usd("450.00")
                reconciliation.unallocated shouldBeNumerically usd("50.00")
            }

            test("records of other payments are ignored") {
                val payment = payment(usd("500.00"))
                val other = payment(usd("900.00"))
                val mine = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("100.00"), minutesLater(1))
                val theirs = PaymentAllocation.create(UUID.randomUUID(), other, documentY, usd("800.00"), minutesLater(1))
                val theirReversal = PaymentAllocationReversal.create(UUID.randomUUID(), theirs, usd("100.00"), minutesLater(2))
                val theirRefund = RefundRecord.create(UUID.randomUUID(), other, usd("50.00"), PaymentMethod.CARD, minutesLater(3))

                val reconciliation =
                    PaymentReconciliation.reconcile(
                        payment,
                        listOf(mine, theirs),
                        listOf(theirReversal),
                        listOf(theirRefund),
                    )

                reconciliation.grossAllocated shouldBeNumerically usd("100.00")
                reconciliation.allocationReversals shouldBeNumerically usd("0")
                reconciliation.totalRefunded shouldBeNumerically usd("0")
                reconciliation.unallocated shouldBeNumerically usd("400.00")
            }

            test("derived amounts are exact, with no rounding") {
                val payment = payment(usd("100"))
                val allocations =
                    listOf("33.333", "33.333", "33.333").map { amount ->
                        PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd(amount), minutesLater(1))
                    }

                PaymentReconciliation.reconcile(payment, allocations).unallocated shouldBe usd("0.001")
            }

            test("reconciliation modifies neither the records nor the supplied collections") {
                val payment = payment(usd("500.00"))
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("500.00"), minutesLater(1))
                val reversal = PaymentAllocationReversal.create(UUID.randomUUID(), allocation, usd("100.00"), minutesLater(2))
                val allocations = listOf(allocation)
                val reversals = listOf(reversal)

                PaymentReconciliation.reconcile(payment, allocations, reversals)

                allocations shouldContainExactly listOf(allocation)
                reversals shouldContainExactly listOf(reversal)
                allocation.amount shouldBe usd("500.00")
            }

            test("reconciling the same records twice gives equal results") {
                val payment = payment(usd("500.00"))
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("200.00"), minutesLater(1))

                PaymentReconciliation.reconcile(payment, listOf(allocation)) shouldBe
                    PaymentReconciliation.reconcile(payment, listOf(allocation))
            }
        }

        context("validation of supplied histories") {

            val payment = payment(usd("500.00"))

            test("allocations beyond the money available from the payment are rejected") {
                val allocations =
                    listOf(
                        PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("300.00"), minutesLater(1)),
                        PaymentAllocation.create(UUID.randomUUID(), payment, documentY, usd("200.01"), minutesLater(2)),
                    )

                shouldThrow<IllegalArgumentException> {
                    PaymentReconciliation.reconcile(payment, allocations)
                }.message shouldContain "is over-applied"
            }

            test("allocations and refunds together beyond the payment are rejected") {
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("450.00"), minutesLater(1))
                val refund = RefundRecord.create(UUID.randomUUID(), payment, usd("100.00"), PaymentMethod.CASH, minutesLater(2))

                shouldThrow<IllegalArgumentException> {
                    PaymentReconciliation.reconcile(payment, listOf(allocation), refunds = listOf(refund))
                }.message shouldContain "is over-applied"
            }

            test("an allocation in another currency than its payment is rejected") {
                val euroAllocation =
                    PaymentAllocation.restore(UUID.randomUUID(), payment.id, documentX.reference, eur("10.00"), minutesLater(1))

                shouldThrow<IllegalArgumentException> {
                    PaymentReconciliation.reconcile(payment, listOf(euroAllocation))
                }.message shouldContain "currencies must match"
            }

            test("a refund in another currency than its payment is rejected") {
                val euroRefund =
                    RefundRecord.restore(UUID.randomUUID(), payment.id, eur("10.00"), PaymentMethod.CARD, minutesLater(1))

                shouldThrow<IllegalArgumentException> {
                    PaymentReconciliation.reconcile(payment, emptyList(), refunds = listOf(euroRefund))
                }.message shouldContain "currencies must match"
            }

            test("a reversal in another currency than its allocation is rejected") {
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("100.00"), minutesLater(1))
                val euroReversal =
                    PaymentAllocationReversal.restore(UUID.randomUUID(), allocation.id, eur("10.00"), minutesLater(2))

                shouldThrow<IllegalArgumentException> {
                    PaymentReconciliation.reconcile(payment, listOf(allocation), listOf(euroReversal))
                }.message shouldContain "currencies must match"
            }

            test("a record supplied twice is rejected rather than counted twice") {
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, documentX, usd("100.00"), minutesLater(1))

                shouldThrow<IllegalArgumentException> {
                    PaymentReconciliation.reconcile(payment, listOf(allocation, allocation))
                }.message shouldContain "repeat id ${allocation.id}"
            }
        }
    })
