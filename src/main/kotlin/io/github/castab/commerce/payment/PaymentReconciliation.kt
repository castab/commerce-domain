package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.Money
import java.util.Currency
import java.util.UUID

/**
 * What became of one [PaymentRecord]'s money, derived from the immutable records that
 * reference it.
 *
 * ```text
 * netReceived  = paymentAmount - totalRefunded
 * netAllocated = grossAllocated - allocationReversals - refundAllocations
 * unallocated  = netReceived - netAllocated
 * ```
 *
 * For example, a $500 payment with $300 allocated and $100 refunded from its unapplied money
 * has `netReceived` $400, `netAllocated` $300, and `unallocated` $100. Had the $100 instead
 * been refunded out of the allocation (with a [RefundAllocation]), `netAllocated` would be
 * $200 and `unallocated` would be $200: only the $200 that was never applied. The refunded
 * $100 is gone from both.
 *
 * ## Reversals and refunds affect unallocated value differently
 *
 * - A [PaymentAllocationReversal] reduces `netAllocated` without moving money, so
 *   `netReceived` is unchanged and `unallocated` grows by the reversed amount. That value
 *   is still the payment's and can be allocated elsewhere.
 * - A [RefundRecord] reduces `netReceived`: that money has left the business. A
 *   [RefundAllocation] records which applied value the refund unwound, reducing
 *   `netAllocated` by the same amount, so `unallocated` does not grow. Refunded money never
 *   becomes available to allocate again.
 *
 * ## Derived, never stored
 *
 * None of these amounts is a field of the payment or of any record. They are recalculated
 * from the records each time, so they can never disagree with them, and the records remain
 * the only authority. There is no payment status. An application that wants one (unapplied,
 * partially applied, fully applied) can derive it from these amounts.
 *
 * ## Validation
 *
 * [reconcile] rejects a history that cannot be true, rather than deriving nonsense from it.
 * It validates the records as a whole, independent of their timestamps: a history is
 * accepted when the combined records are consistent. The library does not interpret the
 * order of application-supplied timestamps.
 */
class PaymentReconciliation private constructor(
    /** The [PaymentRecord.id] of the reconciled payment. */
    val paymentReference: UUID,
    /** The amount received. */
    val paymentAmount: Money,
    /** The sum of every refund of the payment. Never more than [paymentAmount]. */
    val totalRefunded: Money,
    /** The sum of every allocation of the payment, as originally recorded. */
    val grossAllocated: Money,
    /** The sum of every reversal of the payment's allocations. */
    val allocationReversals: Money,
    /** The sum of every refund allocation that unwinds the payment's allocations. */
    val refundAllocations: Money,
) {
    /** The money the business kept: [paymentAmount] minus [totalRefunded]. */
    val netReceived: Money = paymentAmount - totalRefunded

    /**
     * The money currently applied to documents: [grossAllocated] minus
     * [allocationReversals] and [refundAllocations].
     */
    val netAllocated: Money = grossAllocated - allocationReversals - refundAllocations

    /** The kept money not applied to any document: [netReceived] minus [netAllocated]. Never negative. */
    val unallocated: Money = netReceived - netAllocated

    /** The currency of the payment and of every amount here. */
    val currency: Currency
        get() = paymentAmount.currency

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is PaymentReconciliation &&
            other.paymentReference == paymentReference &&
            other.paymentAmount == paymentAmount &&
            other.totalRefunded == totalRefunded &&
            other.grossAllocated == grossAllocated &&
            other.allocationReversals == allocationReversals &&
            other.refundAllocations == refundAllocations

    override fun hashCode(): Int {
        var result = paymentReference.hashCode()
        result = 31 * result + paymentAmount.hashCode()
        result = 31 * result + totalRefunded.hashCode()
        result = 31 * result + grossAllocated.hashCode()
        result = 31 * result + allocationReversals.hashCode()
        result = 31 * result + refundAllocations.hashCode()
        return result
    }

    override fun toString(): String =
        "PaymentReconciliation(paymentReference=$paymentReference, paymentAmount=$paymentAmount, " +
            "totalRefunded=$totalRefunded, netReceived=$netReceived, grossAllocated=$grossAllocated, " +
            "allocationReversals=$allocationReversals, refundAllocations=$refundAllocations, " +
            "netAllocated=$netAllocated, unallocated=$unallocated)"

    companion object {
        /**
         * Reconciles [payment] against the supplied records.
         *
         * The application supplies the records; nothing is loaded. The collections may
         * contain records of other payments, which are ignored: only allocations and refunds
         * whose `paymentReference` is `payment.id`, and the reversals and refund allocations
         * of those, are counted. No record is modified.
         *
         * @throws IllegalArgumentException if the supplied history is inconsistent:
         * - a collection repeats an id;
         * - an allocation, reversal, refund, or refund allocation of this payment is in
         *   another currency than the payment;
         * - the refunds of the payment together exceed the payment amount;
         * - a refund allocation refers to a refund or an allocation that was not supplied, or
         *   links a refund and an allocation of different payments;
         * - the refund allocations of one refund together exceed that refund;
         * - the reversals of one allocation together exceed that allocation, or its reversals
         *   and refund allocations together reduce it below zero;
         * - the payment's net allocations and refunds together exceed the payment amount.
         *   A reversal makes the reversed amount unapplied again, so it can be allocated
         *   elsewhere. A refund allocation does not: it only shows which applied value a
         *   refund took, and the refunded money is no longer the payment's to allocate.
         */
        @JvmStatic
        @JvmOverloads
        fun reconcile(
            payment: PaymentRecord,
            allocations: Collection<PaymentAllocation>,
            allocationReversals: Collection<PaymentAllocationReversal> = emptyList(),
            refunds: Collection<RefundRecord> = emptyList(),
            refundAllocations: Collection<RefundAllocation> = emptyList(),
        ): PaymentReconciliation {
            val allocationsById = allocations.indexById("payment allocation") { it.id }
            val refundsById = refunds.indexById("refund") { it.id }
            allocationReversals.indexById("payment allocation reversal") { it.id }
            refundAllocations.indexById("refund allocation") { it.id }

            val paymentAllocations = allocations.filter { it.paymentReference == payment.id }
            paymentAllocations.forEach { allocation ->
                require(allocation.currency == payment.currency) {
                    "Payment allocation ${allocation.id} is in ${allocation.currency.currencyCode}, but payment " +
                        "${payment.id} is in ${payment.currency.currencyCode}: currencies must match"
                }
            }
            val paymentAllocationIds = paymentAllocations.mapTo(HashSet()) { it.id }

            val paymentRefunds = refunds.filter { it.paymentReference == payment.id }
            paymentRefunds.forEach { refund ->
                require(refund.currency == payment.currency) {
                    "Refund ${refund.id} is in ${refund.currency.currencyCode}, but payment ${payment.id} is in " +
                        "${payment.currency.currencyCode}: currencies must match"
                }
            }
            val totalRefunded = paymentRefunds.sumIn(payment.currency) { it.amount }
            require(!totalRefunded.exceeds(payment.amount)) {
                "Refunds of payment ${payment.id} total $totalRefunded, which exceeds the payment amount ${payment.amount}"
            }
            val paymentRefundIds = paymentRefunds.mapTo(HashSet()) { it.id }

            val reversals = allocationReversals.filter { it.paymentAllocationReference in paymentAllocationIds }
            val unwound =
                refundAllocations.filter {
                    it.paymentAllocationReference in paymentAllocationIds || it.refundReference in paymentRefundIds
                }
            unwound.forEach { refundAllocation ->
                val refund =
                    requireNotNull(refundsById[refundAllocation.refundReference]) {
                        "Refund allocation ${refundAllocation.id} refers to refund ${refundAllocation.refundReference}, " +
                            "which was not supplied"
                    }
                val allocation =
                    requireNotNull(allocationsById[refundAllocation.paymentAllocationReference]) {
                        "Refund allocation ${refundAllocation.id} refers to payment allocation " +
                            "${refundAllocation.paymentAllocationReference}, which was not supplied"
                    }
                require(refund.paymentReference == allocation.paymentReference) {
                    "Refund allocation ${refundAllocation.id} links refund ${refund.id} of payment " +
                        "${refund.paymentReference} to allocation ${allocation.id} of payment " +
                        "${allocation.paymentReference}: they must belong to the same payment"
                }
            }
            unwound.groupBy { it.refundReference }.forEach { (refundId, ofRefund) ->
                val refund = refundsById.getValue(refundId)
                ofRefund.forEach { refundAllocation ->
                    require(refundAllocation.currency == refund.currency) {
                        "Refund allocation ${refundAllocation.id} is in ${refundAllocation.currency.currencyCode}, but " +
                            "refund ${refund.id} is in ${refund.currency.currencyCode}: currencies must match"
                    }
                }
                val allocated = ofRefund.sumIn(refund.currency) { it.amount }
                require(!allocated.exceeds(refund.amount)) {
                    "Refund allocations of refund ${refund.id} total $allocated, which exceeds the refund amount " +
                        "${refund.amount}"
                }
            }
            requireAllocationsNotOverReduced(paymentAllocations, reversals, unwound)

            val reconciliation =
                PaymentReconciliation(
                    paymentReference = payment.id,
                    paymentAmount = payment.amount,
                    totalRefunded = totalRefunded,
                    grossAllocated = paymentAllocations.sumIn(payment.currency) { it.amount },
                    allocationReversals = reversals.sumIn(payment.currency) { it.amount },
                    refundAllocations = unwound.sumIn(payment.currency) { it.amount },
                )
            require(reconciliation.unallocated.amount.signum() >= 0) {
                "Payment ${payment.id} of ${payment.amount} is over-applied: ${reconciliation.netAllocated} " +
                    "remains allocated and ${reconciliation.totalRefunded} was refunded"
            }
            return reconciliation
        }
    }
}

/**
 * Checks, for each allocation, that its reversals and refund allocations are in its
 * currency, that its reversals do not exceed it, and that reversals and refund allocations
 * together do not reduce it below zero.
 */
@JvmSynthetic
internal fun requireAllocationsNotOverReduced(
    allocations: Collection<PaymentAllocation>,
    reversals: Collection<PaymentAllocationReversal>,
    refundAllocations: Collection<RefundAllocation>,
) {
    val reversalsByAllocation = reversals.groupBy { it.paymentAllocationReference }
    val refundAllocationsByAllocation = refundAllocations.groupBy { it.paymentAllocationReference }
    allocations.forEach { allocation ->
        val ofReversals = reversalsByAllocation[allocation.id].orEmpty()
        val ofRefundAllocations = refundAllocationsByAllocation[allocation.id].orEmpty()
        ofReversals.forEach { reversal ->
            require(reversal.currency == allocation.currency) {
                "Payment allocation reversal ${reversal.id} is in ${reversal.currency.currencyCode}, but allocation " +
                    "${allocation.id} is in ${allocation.currency.currencyCode}: currencies must match"
            }
        }
        ofRefundAllocations.forEach { refundAllocation ->
            require(refundAllocation.currency == allocation.currency) {
                "Refund allocation ${refundAllocation.id} is in ${refundAllocation.currency.currencyCode}, but " +
                    "allocation ${allocation.id} is in ${allocation.currency.currencyCode}: currencies must match"
            }
        }
        val reversed = ofReversals.sumIn(allocation.currency) { it.amount }
        require(!reversed.exceeds(allocation.amount)) {
            "Reversals of payment allocation ${allocation.id} total $reversed, which exceeds the allocation " +
                "amount ${allocation.amount}"
        }
        val unwound = ofRefundAllocations.sumIn(allocation.currency) { it.amount }
        require(!(reversed + unwound).exceeds(allocation.amount)) {
            "Payment allocation ${allocation.id} of ${allocation.amount} is reduced below zero: $reversed reversed " +
                "and $unwound unwound by refunds"
        }
    }
}

/** The records keyed by id. Rejects a collection that repeats an id, which would count a record twice. */
@JvmSynthetic
internal fun <T> Collection<T>.indexById(
    kind: String,
    id: (T) -> UUID,
): Map<UUID, T> {
    val index = HashMap<UUID, T>(size * 2)
    forEach { record ->
        require(index.putIfAbsent(id(record), record) == null) { "The supplied ${kind}s repeat id ${id(record)}" }
    }
    return index
}

/** The exact sum of [amount] over these records, or zero in [currency] when there are none. */
@JvmSynthetic
internal inline fun <T> Collection<T>.sumIn(
    currency: Currency,
    amount: (T) -> Money,
): Money = fold(Money.zero(currency)) { sum, record -> sum + amount(record) }
