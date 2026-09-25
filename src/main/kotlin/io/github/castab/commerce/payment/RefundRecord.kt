package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Money
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * The immutable fact that [amount] of money received by payment [paymentReference] was
 * returned to the payer, through [method], at [refundedAt].
 *
 * ## A refund belongs to a payment, not to a document
 *
 * Economically, a refund returns money that a [PaymentRecord] brought in, so it references
 * that payment: `RefundRecord → PaymentRecord`. It does not reference a [FinancialDocument].
 * When the refunded money had been applied to a document, a [RefundAllocation] records which
 * [PaymentAllocation] the refund unwinds. When it came from the payment's unapplied money,
 * no refund allocation is needed at all.
 *
 * ## Method
 *
 * [method] is how the money actually left the business, which need not be how it arrived.
 * A check payment may be refunded in cash, and a debit card payment may be refunded in
 * cash. Whether a business or processor allows a combination is application policy. The
 * library only records what happened.
 *
 * ## A refund is not an allocation reversal
 *
 * A refund is money leaving the business. Correcting where money was recorded as applied,
 * with no money moving, is a [PaymentAllocationReversal]. The two are never interchangeable.
 * A refund is also never recorded by modifying or deleting an allocation.
 *
 * ## Validation
 *
 * [create] checks this refund against the payment it refunds. Whether all refunds of a
 * payment together stay within its amount depends on records this one cannot see;
 * [PaymentReconciliation] checks that.
 */
public class RefundRecord private constructor(
    /** The identity of this refund, chosen by the application. */
    public val id: UUID,
    /** The [PaymentRecord.id] of the payment whose money was returned. */
    public val paymentReference: UUID,
    /** The money returned. Strictly positive, in the payment's currency. */
    public val amount: Money,
    /** The instrument the money was returned through. May differ from the payment's. */
    public val method: PaymentMethod,
    /** When the money was returned. */
    public val refundedAt: Instant,
    /**
     * The refund's identity in an external system, such as a processor's refund id, or
     * `null` when there is none, as for a cash refund issued by hand.
     */
    public val externalReference: ExternalRefundReference?,
) {

    init {
        require(amount.isPositive()) { "Refund $id must have a positive amount, but was $amount" }
    }

    /** The currency of the refund: the currency of [amount]. */
    public val currency: Currency
        get() = amount.currency

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RefundRecord &&
            other.id == id &&
            other.paymentReference == paymentReference &&
            other.amount == amount &&
            other.method == method &&
            other.refundedAt == refundedAt &&
            other.externalReference == externalReference

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + paymentReference.hashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + refundedAt.hashCode()
        result = 31 * result + (externalReference?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RefundRecord(id=$id, paymentReference=$paymentReference, amount=$amount, method=$method, " +
            "refundedAt=$refundedAt, externalReference=$externalReference)"

    public companion object {

        /**
         * Records that [amount] of [payment] was returned to the payer. The refund stores
         * `payment.id`, never the payment itself. [payment] is not modified.
         *
         * [method] is not compared with the payment's method.
         *
         * @throws IllegalArgumentException if [amount] is not strictly positive, is in a
         * different currency from the payment, or exceeds the payment amount.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            id: UUID,
            payment: PaymentRecord,
            amount: Money,
            method: PaymentMethod,
            refundedAt: Instant,
            externalReference: ExternalRefundReference? = null,
        ): RefundRecord {
            require(amount.currency == payment.currency) {
                "Refund $id is in ${amount.currency.currencyCode}, but payment ${payment.id} is in " +
                    "${payment.currency.currencyCode}: currencies must match"
            }
            require(!amount.exceeds(payment.amount)) {
                "Refund $id of $amount exceeds the amount of payment ${payment.id}, ${payment.amount}"
            }
            return RefundRecord(id, payment.id, amount, method, refundedAt, externalReference)
        }

        /**
         * Reconstructs a previously created refund from its stored reference, for use by
         * persistence adapters. Record new refunds with [create].
         *
         * @throws IllegalArgumentException if [amount] is not strictly positive.
         */
        @JvmStatic
        @JvmOverloads
        public fun restore(
            id: UUID,
            paymentReference: UUID,
            amount: Money,
            method: PaymentMethod,
            refundedAt: Instant,
            externalReference: ExternalRefundReference? = null,
        ): RefundRecord = RefundRecord(id, paymentReference, amount, method, refundedAt, externalReference)
    }
}
