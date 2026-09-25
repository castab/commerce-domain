package io.github.castab.commerce.payment.adapter

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.ExternalRefundReference
import io.github.castab.commerce.payment.PaymentMethod
import java.net.URI
import java.time.Instant
import java.util.Collections
import java.util.UUID

/**
 * An extensible identity for an external payment provider, chosen by the consuming application.
 * Its exact string is the `provider` in existing external payment and refund references.
 * It is not a closed list and carries no provider-specific behavior.
 */
@JvmInline
value class PaymentProviderId(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value == value.trim() && value.none(Character::isISOControl)) {
            "A payment provider id must be non-blank, trimmed, and contain no control characters"
        }
    }

    override fun toString(): String = value
}

/**
 * Identifies one provider event by `(provider, eventId)`, independently of the provider's
 * payment or refund object. This provider-owned identity is the idempotency key; a
 * provider may deliver the same event repeatedly.
 */
data class ProviderEventReference(
    val provider: PaymentProviderId,
    val eventId: String,
) {
    init {
        require(eventId.isNotBlank()) { "A provider event id must not be blank" }
    }
}

/** Operations an adapter can perform. Observation-only adapters may support none of these. */
enum class PaymentAdapterCapability {
    PAYMENT_INITIATION,
    HOSTED_CHECKOUT,
    ASYNC_PAYMENT_CONFIRMATION,
    REFUNDS,
    PARTIAL_REFUNDS,
}

/** A snapshot of one adapter's advertised abilities; the set is defensively copied. */
class PaymentAdapterCapabilities(
    val provider: PaymentProviderId,
    capabilities: Set<PaymentAdapterCapability>,
) {
    val capabilities: Set<PaymentAdapterCapability> = Collections.unmodifiableSet(capabilities.toSet())

    fun supports(capability: PaymentAdapterCapability): Boolean = capability in capabilities
}

/**
 * A payment UUID and amount authorized by the consuming application, including for
 * observation-only rails. The application persists the expected instruction and never derives this amount from a UI or
 * provider observation. A later success must match it numerically in the same currency.
 */
data class AuthorizedPayment(
    val paymentId: UUID,
    val provider: PaymentProviderId,
    val amount: Money,
) {
    init {
        require(amount.amount.signum() > 0) { "Authorized payment $paymentId must have a positive amount" }
    }
}

/** Asks an initiating adapter to prepare [payment]; the consuming application has authorized its amount. */
data class PreparePayment(
    val payment: AuthorizedPayment,
    val description: String? = null,
) {
    init {
        require(description == null || description.isNotBlank()) { "Payment description must not be blank" }
    }
}

/**
 * The provider-side payment object prepared for the application-supplied [paymentId].
 * [providerReference] identifies the provider object, not an event. A checkout URI and
 * expiry are optional because many payment rails have no hosted checkout.
 */
data class PaymentPrepared(
    val paymentId: UUID,
    val providerReference: ExternalPaymentReference,
    val checkoutUri: URI? = null,
    val expiresAt: Instant? = null,
) {
    init {
        require(checkoutUri == null || checkoutUri.isAbsolute) { "Checkout URI must be absolute" }
    }
}

/**
 * A verified provider observation that money was received; delivery may be repeated.
 * [paymentId] is application-supplied, [providerReference] is provider-owned, and [event]
 * identifies this observation. The consuming application must compare [amount] with its authorization
 * before creating a [io.github.castab.commerce.payment.PaymentRecord].
 */
data class PaymentSucceeded(
    val paymentId: UUID,
    val providerReference: ExternalPaymentReference,
    val event: ProviderEventReference,
    val amount: Money,
    val method: PaymentMethod,
    val occurredAt: Instant,
) {
    init {
        require(amount.amount.signum() > 0) { "Succeeded payment $paymentId must have a positive amount" }
        require(providerReference.provider == event.provider.value) { "Payment provider and event provider must match" }
    }
}

/**
 * A verified provider observation of failure; delivery may be repeated. It does not
 * create a [io.github.castab.commerce.payment.PaymentRecord] or undo money received.
 */
data class PaymentFailed(
    val paymentId: UUID,
    val event: ProviderEventReference,
    val occurredAt: Instant,
    val providerReference: ExternalPaymentReference? = null,
) {
    init {
        require(providerReference == null || providerReference.provider == event.provider.value) {
            "Payment provider and event provider must match"
        }
    }
}

/**
 * A request to return [amount] from a known payment. [refundId] and [paymentId]
 * are application-supplied UUIDs; [providerReference] identifies the provider-side payment.
 * The application must check capability and remaining refundable value before dispatch.
 */
data class RequestRefund(
    val refundId: UUID,
    val paymentId: UUID,
    val providerReference: ExternalPaymentReference,
    val amount: Money,
) {
    init {
        require(amount.amount.signum() > 0) { "Requested refund $refundId must have a positive amount" }
    }
}

/**
 * A verified provider observation that money was returned; delivery may be repeated.
 * [refundId] and [paymentId] are application-supplied. [providerReference] identifies the
 * provider-side refund, while [event] identifies this delivery's event. The consuming application checks
 * the request and cumulative refunds before recording money movement.
 */
data class RefundSucceeded(
    val refundId: UUID,
    val paymentId: UUID,
    val providerReference: ExternalRefundReference,
    val event: ProviderEventReference,
    val amount: Money,
    val method: PaymentMethod,
    val occurredAt: Instant,
) {
    init {
        require(amount.amount.signum() > 0) { "Succeeded refund $refundId must have a positive amount" }
        require(providerReference.provider == event.provider.value) { "Refund provider and event provider must match" }
    }
}

/** A verified, repeatable provider observation of refund failure; it records no money leaving. */
data class RefundFailed(
    val refundId: UUID,
    val paymentId: UUID,
    val event: ProviderEventReference,
    val occurredAt: Instant,
    val providerReference: ExternalRefundReference? = null,
) {
    init {
        require(providerReference == null || providerReference.provider == event.provider.value) {
            "Refund provider and event provider must match"
        }
    }
}

/** The application-owned target of a provider event. These UUIDs are supplied by the application. */
sealed interface ProviderEventTarget {
    data class Payment(
        val paymentId: UUID,
    ) : ProviderEventTarget

    data class Refund(
        val refundId: UUID,
        val paymentId: UUID,
    ) : ProviderEventTarget
}

/**
 * Minimal durable evidence of processing an event, without its raw provider payload.
 * Applications enforce unique [event] and persist this receipt in the same transaction
 * as its payment, refund, or application-owned request-status effect.
 */
data class ProviderEventReceipt(
    val event: ProviderEventReference,
    val target: ProviderEventTarget,
    val occurredAt: Instant,
    val receivedAt: Instant,
)
