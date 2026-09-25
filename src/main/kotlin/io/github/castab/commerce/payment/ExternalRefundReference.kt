package io.github.castab.commerce.payment

/**
 * The identity of a [RefundRecord]'s transaction in an external system, such as a payment
 * processor.
 *
 * For example `ExternalRefundReference(provider = "stripe", reference = "re_456")`. The
 * library never interprets either value and depends on no processor SDK.
 *
 * Uniqueness is not enforced here. If the application needs each external refund to be
 * recorded only once, its persistence layer should enforce that.
 *
 * This is deliberately a different type from [ExternalPaymentReference], so that a
 * payment's transaction id cannot be recorded as a refund's by mistake.
 *
 * @property provider The external system, for example `"stripe"` or `"paypal"`. Must not
 * be blank.
 * @property reference The refund's identifier in that system. Must not be blank.
 */
data class ExternalRefundReference(
    val provider: String,
    val reference: String,
) {
    init {
        require(provider.isNotBlank()) { "An external refund reference must have a non-blank provider" }
        require(reference.isNotBlank()) { "An external refund reference must have a non-blank reference" }
    }
}
