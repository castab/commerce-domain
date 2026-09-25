package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Money
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * The immutable fact that [amount] of payment [paymentReference] was applied to the
 * financial document identified by [financialDocumentReference], while that document was at
 * exactly that version.
 *
 * An allocation is reconciliation, not money movement. The money moved once, when the
 * [PaymentRecord] was received. An allocation only records where that money was applied.
 * One payment may have many allocations, to one document or to several, and one document
 * lineage may receive allocations from many payments.
 *
 * ## Exact snapshot and enduring lineage
 *
 * [financialDocumentReference] carries two meanings at once, without a second identifier:
 *
 * - The whole reference, `(id, version)`, is the exact snapshot the allocation was made
 *   against: what the commercial obligation looked like when the money was applied. To find
 *   allocations made against one snapshot, compare the whole reference:
 *   `allocation.financialDocumentReference == FinancialDocumentReference(d, Version.of(2))`.
 * - Its `id` alone is the enduring commercial obligation, shared by every version of the
 *   document. To find every allocation belonging to a lineage, compare only the id:
 *   `allocation.financialDocumentReference.id == d`.
 *
 * ## Allocations never roll forward
 *
 * When the document advances (a change order, `toQuote`, `toInvoice`), existing allocations
 * stay attached to the snapshot they were made against. A deposit applied to quote `D/v2`
 * remains an allocation to `D/v2` after the document becomes invoice `D/v4`: rewriting it to
 * `D/v4` would falsify history. Reconciliation of the current snapshot finds it through the
 * shared lineage id instead. See [FinancialDocumentReconciliation].
 *
 * ## Creation and restoration
 *
 * [create] takes the actual [PaymentRecord] and [FinancialDocument], so it can check that
 * their currencies agree, and stores only their references. [restore] rebuilds a stored
 * allocation from those references. Neither retains the payment or the document.
 *
 * The library does not restrict which stages accept payments. Whether an estimate, quote,
 * or invoice may receive money, and when, is the application's policy.
 *
 * An allocation is never modified or deleted. A wrong allocation is corrected by a
 * [PaymentAllocationReversal], and money refunded out of an allocation is recorded by a
 * [RefundAllocation].
 */
class PaymentAllocation private constructor(
    /** The identity of this allocation, chosen by the application. */
    val id: UUID,
    /** The [PaymentRecord.id] of the payment whose money was applied. */
    val paymentReference: UUID,
    /**
     * The exact financial-document snapshot the money was applied to. Its `id` also
     * identifies the document's whole lineage.
     */
    val financialDocumentReference: FinancialDocumentReference,
    /** The amount applied. Strictly positive, in the payment's currency. */
    val amount: Money,
    /** When the money was applied. */
    val allocatedAt: Instant,
) {
    init {
        require(amount.isPositive()) { "Payment allocation $id must have a positive amount, but was $amount" }
    }

    /** The currency of the allocation: the currency of [amount]. */
    val currency: Currency
        get() = amount.currency

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is PaymentAllocation &&
            other.id == id &&
            other.paymentReference == paymentReference &&
            other.financialDocumentReference == financialDocumentReference &&
            other.amount == amount &&
            other.allocatedAt == allocatedAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + paymentReference.hashCode()
        result = 31 * result + financialDocumentReference.hashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + allocatedAt.hashCode()
        return result
    }

    override fun toString(): String =
        "PaymentAllocation(id=$id, paymentReference=$paymentReference, " +
            "financialDocumentReference=$financialDocumentReference, amount=$amount, allocatedAt=$allocatedAt)"

    companion object {
        /**
         * Records that [amount] of [payment] was applied to [financialDocument], as that
         * exact snapshot.
         *
         * The allocation stores `payment.id` and `financialDocument.reference`, never the
         * payment or the document themselves. Any stage may receive an allocation.
         *
         * Whether the payment still has [amount] available, given its other allocations and
         * refunds, depends on records this function cannot see. [PaymentReconciliation]
         * checks that.
         *
         * @throws IllegalArgumentException if [amount] is not strictly positive, is not in
         * the currency of both the payment and the document, or exceeds the payment amount.
         */
        @JvmStatic
        fun create(
            id: UUID,
            payment: PaymentRecord,
            financialDocument: FinancialDocument,
            amount: Money,
            allocatedAt: Instant,
        ): PaymentAllocation {
            require(amount.currency == payment.currency) {
                "Payment allocation $id is in ${amount.currency.currencyCode}, but payment ${payment.id} " +
                    "is in ${payment.currency.currencyCode}: currencies must match"
            }
            require(financialDocument.currency == payment.currency) {
                "Payment allocation $id applies ${payment.currency.currencyCode} payment ${payment.id} to " +
                    "financial document ${financialDocument.reference.describe()} in " +
                    "${financialDocument.currency.currencyCode}: currencies must match"
            }
            require(!amount.exceeds(payment.amount)) {
                "Payment allocation $id of $amount exceeds the amount of payment ${payment.id}, ${payment.amount}"
            }
            return PaymentAllocation(id, payment.id, financialDocument.reference, amount, allocatedAt)
        }

        /**
         * Reconstructs a previously created allocation from its stored references, for use
         * by persistence adapters.
         *
         * Only checks that [amount] is strictly positive. Consistency with the payment, the
         * document, and the other records is checked by [PaymentReconciliation] and
         * [FinancialDocumentReconciliation]. Record new allocations with [create].
         *
         * @throws IllegalArgumentException if [amount] is not strictly positive.
         */
        @JvmStatic
        fun restore(
            id: UUID,
            paymentReference: UUID,
            financialDocumentReference: FinancialDocumentReference,
            amount: Money,
            allocatedAt: Instant,
        ): PaymentAllocation = PaymentAllocation(id, paymentReference, financialDocumentReference, amount, allocatedAt)
    }
}

/** `D/v3` style text for messages. */
@JvmSynthetic
internal fun FinancialDocumentReference.describe(): String = "$id/$version"
