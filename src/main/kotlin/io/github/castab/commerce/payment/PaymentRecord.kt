package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Money
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * The immutable fact that money was received: this [amount], through this [method], at
 * [receivedAt].
 *
 * ## A payment belongs to no document
 *
 * A payment records money movement only. It has no financial-document reference, no
 * allocation, no balance, and no refunded amount. Where the money was applied is recorded
 * separately by [PaymentAllocation]s, because a payment exists on its own terms: it may be
 * entirely unapplied (money received before anything was invoiced), partially applied,
 * applied to one [FinancialDocument], or split across several. Embedding a document in the
 * payment could express only one of these.
 *
 * Money returned to the payer is recorded by [RefundRecord]s that reference this payment,
 * never by changing it.
 *
 * ## Immutability
 *
 * Nothing modifies a payment. Mistakes are corrected by appending records: an
 * [PaymentAllocationReversal] for a wrong allocation, a [RefundRecord] for money actually
 * returned. Everything derived from a payment (how much is allocated, refunded, or still
 * unapplied) is calculated by [PaymentReconciliation] from the records, never stored on the
 * payment.
 *
 * The [id] is supplied by the application. The library never generates identifiers.
 *
 * @throws IllegalArgumentException if [amount] is not strictly positive.
 */
class PaymentRecord
    @JvmOverloads
    constructor(
        /** The identity of this payment, chosen by the application. */
        val id: UUID,
        /** The money received. Strictly positive. Its currency is the payment's [currency]. */
        val amount: Money,
        /** The instrument the money was received through. */
        val method: PaymentMethod,
        /** When the money was received. */
        val receivedAt: Instant,
        /**
         * The payment's identity in an external system, such as a processor's transaction id,
         * or `null` when there is none, as for cash, a check, or a manually recorded payment.
         */
        val externalReference: ExternalPaymentReference? = null,
    ) {
        init {
            require(amount.isPositive()) { "Payment $id must have a positive amount, but was $amount" }
        }

        /** The currency of the payment: the currency of [amount]. */
        val currency: Currency
            get() = amount.currency

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is PaymentRecord &&
                other.id == id &&
                other.amount == amount &&
                other.method == method &&
                other.receivedAt == receivedAt &&
                other.externalReference == externalReference

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + amount.hashCode()
            result = 31 * result + method.hashCode()
            result = 31 * result + receivedAt.hashCode()
            result = 31 * result + (externalReference?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "PaymentRecord(id=$id, amount=$amount, method=$method, receivedAt=$receivedAt, " +
                "externalReference=$externalReference)"
    }

/** Whether this amount is greater than zero, compared numerically rather than by scale. */
@JvmSynthetic
internal fun Money.isPositive(): Boolean = amount.signum() > 0

/**
 * Whether this amount is numerically greater than [other].
 *
 * @throws IllegalArgumentException if the currencies differ.
 */
@JvmSynthetic
internal fun Money.exceeds(other: Money): Boolean = (this - other).amount.signum() > 0
