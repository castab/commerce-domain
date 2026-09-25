package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.Money
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * The immutable fact that [amount] of an earlier [PaymentAllocation] was recorded in error
 * and no longer counts as applied.
 *
 * A reversal corrects bookkeeping. For example, $500 applied to the wrong document can be
 * corrected by reversing it and allocating it again:
 *
 * ```text
 * Payment P1                 $500
 * Allocation A1              $500 → Document X/v3
 * AllocationReversal R1      $500 → reverses A1
 * Allocation A2              $500 → Document Y/v2
 * ```
 *
 * The history keeps all three records. A1 is never edited or deleted. Partial reversals are
 * supported: reversing $200 of A1 leaves $300 of it applied.
 *
 * ## A reversal is not a refund
 *
 * A reversal moves no money. The payer is owed nothing because of it, and the reversed
 * amount becomes unapplied money of the same payment, available to be allocated again.
 * Money that actually leaves the business is recorded by a [RefundRecord], and the applied
 * value it unwinds by a [RefundAllocation]. Never record a correction as a refund, and never
 * record a refund as a reversal.
 *
 * ## Validation
 *
 * [create] checks this reversal against the allocation it reverses. Whether all reversals
 * of an allocation together stay within its amount depends on records this one cannot see;
 * [PaymentReconciliation] and [FinancialDocumentReconciliation] check that.
 */
public class PaymentAllocationReversal private constructor(
    /** The identity of this reversal, chosen by the application. */
    public val id: UUID,
    /** The [PaymentAllocation.id] of the allocation being reversed. */
    public val paymentAllocationReference: UUID,
    /** The amount no longer applied. Strictly positive, in the allocation's currency. */
    public val amount: Money,
    /** When the reversal was recorded. */
    public val reversedAt: Instant,
    /** Why the allocation was reversed, for auditing, or `null`. Never blank. */
    public val reason: String?,
) {

    init {
        require(amount.isPositive()) {
            "Payment allocation reversal $id must have a positive amount, but was $amount"
        }
        require(reason == null || reason.isNotBlank()) {
            "Payment allocation reversal $id must have a non-blank reason, or none"
        }
    }

    /** The currency of the reversal: the currency of [amount]. */
    public val currency: Currency
        get() = amount.currency

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is PaymentAllocationReversal &&
            other.id == id &&
            other.paymentAllocationReference == paymentAllocationReference &&
            other.amount == amount &&
            other.reversedAt == reversedAt &&
            other.reason == reason

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + paymentAllocationReference.hashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + reversedAt.hashCode()
        result = 31 * result + (reason?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PaymentAllocationReversal(id=$id, paymentAllocationReference=$paymentAllocationReference, " +
            "amount=$amount, reversedAt=$reversedAt, reason=$reason)"

    public companion object {

        /**
         * Records that [amount] of [allocation] is reversed. The reversal stores
         * `allocation.id`, never the allocation itself. [allocation] is not modified.
         *
         * @throws IllegalArgumentException if [amount] is not strictly positive, is in a
         * different currency from the allocation, or exceeds the allocation amount, or if
         * [reason] is blank.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            id: UUID,
            allocation: PaymentAllocation,
            amount: Money,
            reversedAt: Instant,
            reason: String? = null,
        ): PaymentAllocationReversal {
            require(amount.currency == allocation.currency) {
                "Payment allocation reversal $id is in ${amount.currency.currencyCode}, but allocation " +
                    "${allocation.id} is in ${allocation.currency.currencyCode}: currencies must match"
            }
            require(!amount.exceeds(allocation.amount)) {
                "Payment allocation reversal $id of $amount exceeds the amount of allocation " +
                    "${allocation.id}, ${allocation.amount}"
            }
            return PaymentAllocationReversal(id, allocation.id, amount, reversedAt, reason)
        }

        /**
         * Reconstructs a previously created reversal from its stored reference, for use by
         * persistence adapters. Record new reversals with [create].
         *
         * @throws IllegalArgumentException if [amount] is not strictly positive or [reason]
         * is blank.
         */
        @JvmStatic
        @JvmOverloads
        public fun restore(
            id: UUID,
            paymentAllocationReference: UUID,
            amount: Money,
            reversedAt: Instant,
            reason: String? = null,
        ): PaymentAllocationReversal = PaymentAllocationReversal(id, paymentAllocationReference, amount, reversedAt, reason)
    }
}
