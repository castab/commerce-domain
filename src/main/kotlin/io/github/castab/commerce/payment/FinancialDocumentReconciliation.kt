package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Money
import java.util.Currency

/**
 * How much money is applied to a financial document's whole lineage, and what remains
 * owed, derived from the current snapshot and the immutable records that reference it.
 *
 * ```text
 * netApplied = grossAllocated - allocationReversals - refundAllocations
 * balance    = documentTotal - netApplied
 * ```
 *
 * ## Lineage-level reconciliation
 *
 * A document's snapshots share one id, and together they are one enduring commercial
 * obligation. Every [PaymentAllocation] whose `financialDocumentReference.id` is the
 * document's id counts, whichever version it was made against:
 *
 * ```text
 * D/v2 Quote     total $1,000   ← $300 deposit allocated to D/v2
 * D/v3 Quote     total $1,200
 * D/v4 Invoice   total $1,200
 * D/v5 Invoice   total $1,250   ← reconciled: netApplied $300, balance $950
 * ```
 *
 * The deposit stays an allocation to `D/v2`. Nothing is rewritten to `D/v5`; the current
 * snapshot supplies only [documentTotal].
 *
 * ## Derived, never stored
 *
 * These amounts belong to this derived result, never to [FinancialDocument], which holds no
 * settlement state at all. There is no payment status. An application that wants one
 * (unpaid, partially paid, paid, overpaid) can derive it from [netApplied] and [balance]. A
 * negative [balance] means more is applied than the document currently totals.
 */
class FinancialDocumentReconciliation private constructor(
    /** The snapshot that was reconciled, normally the lineage's latest. */
    val documentReference: FinancialDocumentReference,
    /** The total of the reconciled snapshot. */
    val documentTotal: Money,
    /** The sum of every allocation to any version of the document, as originally recorded. */
    val grossAllocated: Money,
    /** The sum of every reversal of those allocations. */
    val allocationReversals: Money,
    /** The sum of every refund allocation that unwinds those allocations. */
    val refundAllocations: Money,
) {

    /**
     * The money currently applied to the document: [grossAllocated] minus
     * [allocationReversals] and [refundAllocations].
     */
    val netApplied: Money = grossAllocated - allocationReversals - refundAllocations

    /** What remains owed: [documentTotal] minus [netApplied]. Negative when over-applied. */
    val balance: Money = documentTotal - netApplied

    /** The currency of the document and of every amount here. */
    val currency: Currency
        get() = documentTotal.currency

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is FinancialDocumentReconciliation &&
            other.documentReference == documentReference &&
            other.documentTotal == documentTotal &&
            other.grossAllocated == grossAllocated &&
            other.allocationReversals == allocationReversals &&
            other.refundAllocations == refundAllocations

    override fun hashCode(): Int {
        var result = documentReference.hashCode()
        result = 31 * result + documentTotal.hashCode()
        result = 31 * result + grossAllocated.hashCode()
        result = 31 * result + allocationReversals.hashCode()
        result = 31 * result + refundAllocations.hashCode()
        return result
    }

    override fun toString(): String =
        "FinancialDocumentReconciliation(documentReference=$documentReference, documentTotal=$documentTotal, " +
            "grossAllocated=$grossAllocated, allocationReversals=$allocationReversals, " +
            "refundAllocations=$refundAllocations, netApplied=$netApplied, balance=$balance)"

    companion object {

        /**
         * Reconciles the lineage of [document] against the supplied records, using
         * [document]'s total as the amount owed.
         *
         * The application supplies the records; nothing is loaded. The collections may
         * contain records of other documents, which are ignored: only allocations whose
         * `financialDocumentReference.id` is `document.id`, and the reversals and refund
         * allocations of those, are counted. No record is modified.
         *
         * Only document-side consistency is checked here. Whether each payment can fund its
         * allocations, and whether refund allocations fit their refunds, is checked by
         * [PaymentReconciliation].
         *
         * @throws IllegalArgumentException if the supplied history is inconsistent:
         * - a collection repeats an id;
         * - an allocation to the lineage refers to a later version than [document], so
         *   [document] is not the current snapshot;
         * - an allocation to the lineage, or one of its reversals or refund allocations, is
         *   in another currency than [document];
         * - the reversals of one allocation together exceed that allocation, or its reversals
         *   and refund allocations together reduce it below zero.
         */
        @JvmStatic
        @JvmOverloads
        fun reconcile(
            document: FinancialDocument,
            allocations: Collection<PaymentAllocation>,
            allocationReversals: Collection<PaymentAllocationReversal> = emptyList(),
            refundAllocations: Collection<RefundAllocation> = emptyList(),
        ): FinancialDocumentReconciliation {
            allocations.indexById("payment allocation") { it.id }
            allocationReversals.indexById("payment allocation reversal") { it.id }
            refundAllocations.indexById("refund allocation") { it.id }

            val lineageAllocations = allocations.filter { it.financialDocumentReference.id == document.id }
            lineageAllocations.forEach { allocation ->
                require(allocation.financialDocumentReference.version <= document.version) {
                    "Payment allocation ${allocation.id} refers to ${allocation.financialDocumentReference.describe()}, " +
                        "which is later than the reconciled snapshot ${document.reference.describe()}: " +
                        "reconcile the latest snapshot"
                }
                require(allocation.currency == document.currency) {
                    "Payment allocation ${allocation.id} is in ${allocation.currency.currencyCode}, but financial " +
                        "document ${document.reference.describe()} is in ${document.currency.currencyCode}: " +
                        "currencies must match"
                }
            }
            val lineageAllocationIds = lineageAllocations.mapTo(HashSet()) { it.id }
            val reversals = allocationReversals.filter { it.paymentAllocationReference in lineageAllocationIds }
            val unwound = refundAllocations.filter { it.paymentAllocationReference in lineageAllocationIds }
            requireAllocationsNotOverReduced(lineageAllocations, reversals, unwound)

            return FinancialDocumentReconciliation(
                documentReference = document.reference,
                documentTotal = document.total,
                grossAllocated = lineageAllocations.sumIn(document.currency) { it.amount },
                allocationReversals = reversals.sumIn(document.currency) { it.amount },
                refundAllocations = unwound.sumIn(document.currency) { it.amount },
            )
        }
    }
}
