package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.Money
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * The immutable fact that [amount] of refund [refundReference] unwinds value previously
 * applied by allocation [paymentAllocationReference].
 *
 * A refund allocation completes the audit path from a document to the money that left:
 *
 * ```text
 * FinancialDocument D/v4
 *         ↑
 * PaymentAllocation A1      $500 → D/v4
 *         ↑
 * Payment P1                $500
 *         ↑
 * Refund R1                 $100 of P1
 *         ↓
 * RefundAllocation RA1      $100 of R1 unwinds A1
 * ```
 *
 * The refunded amount no longer counts as applied to the document. A1 itself is unchanged.
 *
 * A refund allocation never increases unallocated value or makes refunded money reusable.
 * The refunded money has left the business. To the extent a refund unwinds applied value,
 * both the payment's net received and net allocated amounts are reduced correspondingly;
 * any portion refunded from previously unapplied value reduces its unallocated amount. Only
 * a [PaymentAllocationReversal], which moves no money, makes applied value unapplied and
 * reusable.
 *
 * ## Optional
 *
 * A refund does not need a refund allocation. Money refunded from a payment's unapplied
 * portion was never applied to any document, so there is nothing to unwind: a $500 payment
 * with $300 allocated can refund $100 of the remaining $200 with no refund allocation. A
 * refund may also be split, part from unapplied money and part from one or more
 * allocations, and one allocation may be partially unwound.
 *
 * ## Validation
 *
 * The refund and the allocation must belong to the same payment. [create] checks this, and
 * that the amount fits within both. Whether all refund allocations together stay within
 * the refund, and whether reversals and refund allocations together stay within the
 * allocation, depends on records this one cannot see; [PaymentReconciliation] and
 * [FinancialDocumentReconciliation] check that.
 */
class RefundAllocation private constructor(
    /** The identity of this refund allocation, chosen by the application. */
    val id: UUID,
    /** The [RefundRecord.id] of the refund that returned the money. */
    val refundReference: UUID,
    /** The [PaymentAllocation.id] of the allocation whose applied value is unwound. */
    val paymentAllocationReference: UUID,
    /** The amount unwound. Strictly positive, in the payment's currency. */
    val amount: Money,
    /** When the refunded amount was attributed to the allocation. */
    val allocatedAt: Instant,
) {

    init {
        require(amount.isPositive()) { "Refund allocation $id must have a positive amount, but was $amount" }
    }

    /** The currency of the refund allocation: the currency of [amount]. */
    val currency: Currency
        get() = amount.currency

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RefundAllocation &&
            other.id == id &&
            other.refundReference == refundReference &&
            other.paymentAllocationReference == paymentAllocationReference &&
            other.amount == amount &&
            other.allocatedAt == allocatedAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + refundReference.hashCode()
        result = 31 * result + paymentAllocationReference.hashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + allocatedAt.hashCode()
        return result
    }

    override fun toString(): String =
        "RefundAllocation(id=$id, refundReference=$refundReference, " +
            "paymentAllocationReference=$paymentAllocationReference, amount=$amount, allocatedAt=$allocatedAt)"

    companion object {

        /**
         * Records that [amount] of [refund] unwinds value applied by [allocation]. Stores
         * `refund.id` and `allocation.id`, never the records themselves, and modifies
         * neither.
         *
         * @throws IllegalArgumentException if the refund and the allocation belong to
         * different payments, or [amount] is not strictly positive, is in a different
         * currency, or exceeds the refund amount or the allocation amount.
         */
        @JvmStatic
        fun create(
            id: UUID,
            refund: RefundRecord,
            allocation: PaymentAllocation,
            amount: Money,
            allocatedAt: Instant,
        ): RefundAllocation {
            require(refund.paymentReference == allocation.paymentReference) {
                "Refund allocation $id cannot apply refund ${refund.id} of payment ${refund.paymentReference} " +
                    "to allocation ${allocation.id} of payment ${allocation.paymentReference}: " +
                    "they must belong to the same payment"
            }
            require(amount.currency == refund.currency) {
                "Refund allocation $id is in ${amount.currency.currencyCode}, but refund ${refund.id} is in " +
                    "${refund.currency.currencyCode}: currencies must match"
            }
            require(allocation.currency == refund.currency) {
                "Refund allocation $id applies ${refund.currency.currencyCode} refund ${refund.id} to allocation " +
                    "${allocation.id} in ${allocation.currency.currencyCode}: currencies must match"
            }
            require(!amount.exceeds(refund.amount)) {
                "Refund allocation $id of $amount exceeds the amount of refund ${refund.id}, ${refund.amount}"
            }
            require(!amount.exceeds(allocation.amount)) {
                "Refund allocation $id of $amount exceeds the amount of allocation ${allocation.id}, ${allocation.amount}"
            }
            return RefundAllocation(id, refund.id, allocation.id, amount, allocatedAt)
        }

        /**
         * Reconstructs a previously created refund allocation from its stored references,
         * for use by persistence adapters. Record new refund allocations with [create].
         *
         * @throws IllegalArgumentException if [amount] is not strictly positive.
         */
        @JvmStatic
        fun restore(
            id: UUID,
            refundReference: UUID,
            paymentAllocationReference: UUID,
            amount: Money,
            allocatedAt: Instant,
        ): RefundAllocation = RefundAllocation(id, refundReference, paymentAllocationReference, amount, allocatedAt)
    }
}
