package io.github.castab.commerce.payment

/**
 * The identity of a [PaymentRecord]'s transaction in an external system, such as a
 * payment processor.
 *
 * For example `ExternalPaymentReference(provider = "provider-a", reference = "payment-123")`. The
 * library never interprets either value and depends on no processor SDK. It only records
 * them, so that the application can trace a payment back to its source.
 *
 * Uniqueness is not enforced here. If the application needs each external transaction to
 * be recorded only once, its persistence layer should enforce that.
 *
 * A separate type, [ExternalRefundReference], identifies refunds, so that a refund's
 * transaction id cannot be recorded as a payment's by mistake.
 *
 * @property provider The external system's stable name. Must not be blank.
 * @property reference The transaction's identifier in that system. Must not be blank.
 */
data class ExternalPaymentReference(
    val provider: String,
    val reference: String,
) {
    init {
        require(provider.isNotBlank()) { "An external payment reference must have a non-blank provider" }
        require(reference.isNotBlank()) { "An external payment reference must have a non-blank reference" }
    }
}
