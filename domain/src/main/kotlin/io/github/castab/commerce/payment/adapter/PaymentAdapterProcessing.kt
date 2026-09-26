package io.github.castab.commerce.payment.adapter

import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.payment.RefundRecord
import java.time.Instant

/** Expected domain outcomes. Unexpected adapter or storage failures remain application concerns. */
enum class AdapterRejection {
    UNSUPPORTED_CAPABILITY,
    WRONG_PAYMENT,
    WRONG_REFUND,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    CONFLICTING_REFERENCE,
    ALREADY_RECORDED,
    EXCEEDS_REFUNDABLE,
    EVENT_CONFLICT,
}

/** Decision on a prepared provider payment, before its reference is persisted by the caller. */
sealed interface PreparationDecision {
    data class Accepted(
        val prepared: PaymentPrepared,
    ) : PreparationDecision

    data class Rejected(
        val reason: AdapterRejection,
    ) : PreparationDecision
}

/** Decision on whether the consuming application may ask this adapter to perform a refund. */
sealed interface RefundRequestDecision {
    data class Accepted(
        val request: RequestRefund,
    ) : RefundRequestDecision

    data class Rejected(
        val reason: AdapterRejection,
    ) : RefundRequestDecision
}

/** A decision about a verified provider event. Persist [Accepted.receipt] with its effect atomically. */
sealed interface ProviderEventDecision<out T> {
    data class Accepted<T>(
        val effect: T,
        val receipt: ProviderEventReceipt,
    ) : ProviderEventDecision<T>

    data object AlreadyProcessed : ProviderEventDecision<Nothing>

    data class Rejected(
        val reason: AdapterRejection,
    ) : ProviderEventDecision<Nothing>
}

/** Pure validation of adapter messages against authorized data supplied by the consuming application. */
object PaymentAdapterProcessing {
    /** Checks a preparation result; hosted checkout is optional even for a capable adapter. */
    fun assessPreparation(
        request: PreparePayment,
        prepared: PaymentPrepared,
        capabilities: PaymentAdapterCapabilities,
    ): PreparationDecision {
        if (capabilities.provider != request.payment.provider ||
            !capabilities.supports(PaymentAdapterCapability.PAYMENT_INITIATION)
        ) {
            return PreparationDecision.Rejected(AdapterRejection.UNSUPPORTED_CAPABILITY)
        }
        if (prepared.paymentId != request.payment.paymentId) {
            return PreparationDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
        }
        if (prepared.providerReference.provider != request.payment.provider.value) {
            return PreparationDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (prepared.checkoutUri != null && !capabilities.supports(PaymentAdapterCapability.HOSTED_CHECKOUT)) {
            return PreparationDecision.Rejected(AdapterRejection.UNSUPPORTED_CAPABILITY)
        }
        return PreparationDecision.Accepted(prepared)
    }

    /**
     * Checks a success against the authoritative [expected] amount and prior payment facts.
     * The caller must supply all relevant records and receipts and atomically persist the
     * accepted payment and receipt with unique payment, provider reference, and event keys.
     */
    fun assessPaymentSuccess(
        expected: AuthorizedPayment,
        observation: PaymentSucceeded,
        payments: Collection<PaymentRecord>,
        receipts: Collection<ProviderEventReceipt>,
        receivedAt: Instant,
        prepared: PaymentPrepared? = null,
    ): ProviderEventDecision<PaymentRecord> {
        val target = ProviderEventTarget.Payment(observation.paymentId)
        priorEvent(observation.event, target, receipts)?.let { return it }
        if (observation.paymentId != expected.paymentId) {
            return ProviderEventDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
        }
        if (observation.event.provider != expected.provider) {
            return ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (prepared != null &&
            (prepared.paymentId != expected.paymentId || prepared.providerReference != observation.providerReference)
        ) {
            return ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (observation.amount.currency != expected.amount.currency) {
            return ProviderEventDecision.Rejected(AdapterRejection.CURRENCY_MISMATCH)
        }
        if (observation.amount.amount.compareTo(expected.amount.amount) != 0) {
            return ProviderEventDecision.Rejected(AdapterRejection.AMOUNT_MISMATCH)
        }
        val recorded = payments.firstOrNull { it.id == expected.paymentId }
        if (recorded != null) {
            return ProviderEventDecision.Rejected(
                if (recorded.externalReference == observation.providerReference) {
                    AdapterRejection.ALREADY_RECORDED
                } else {
                    AdapterRejection.CONFLICTING_REFERENCE
                },
            )
        }
        if (payments.any { it.externalReference == observation.providerReference }) {
            return ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        return ProviderEventDecision.Accepted(
            PaymentRecord(
                expected.paymentId,
                observation.amount,
                observation.method,
                observation.occurredAt,
                observation.providerReference,
            ),
            ProviderEventReceipt(observation.event, target, observation.occurredAt, receivedAt),
        )
    }

    /** Failure changes no money record; the returned receipt still needs atomic handling. */
    fun assessPaymentFailure(
        expected: AuthorizedPayment,
        observation: PaymentFailed,
        payments: Collection<PaymentRecord>,
        receipts: Collection<ProviderEventReceipt>,
        receivedAt: Instant,
        prepared: PaymentPrepared? = null,
    ): ProviderEventDecision<Unit> {
        val target = ProviderEventTarget.Payment(observation.paymentId)
        priorEvent(observation.event, target, receipts)?.let { return it }
        if (observation.paymentId != expected.paymentId) {
            return ProviderEventDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
        }
        if (observation.event.provider != expected.provider) {
            return ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (prepared != null &&
            (
                prepared.paymentId != expected.paymentId ||
                    prepared.providerReference.provider != expected.provider.value ||
                    observation.providerReference != null &&
                    observation.providerReference != prepared.providerReference
            )
        ) {
            return ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (payments.any { it.id == expected.paymentId }) {
            return ProviderEventDecision.Rejected(AdapterRejection.ALREADY_RECORDED)
        }
        return ProviderEventDecision.Accepted(
            Unit,
            ProviderEventReceipt(observation.event, target, observation.occurredAt, receivedAt),
        )
    }

    /**
     * Checks a refund request before an adapter attempts it. [refunds] must include every
     * refund already recorded against [payment] so partial refunds cannot exceed it.
     */
    fun assessRefundRequest(
        request: RequestRefund,
        payment: PaymentRecord,
        refunds: Collection<RefundRecord>,
        capabilities: PaymentAdapterCapabilities,
    ): RefundRequestDecision {
        if (request.paymentId != payment.id) return RefundRequestDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
        if (payment.externalReference != request.providerReference) {
            return RefundRequestDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (capabilities.provider.value != request.providerReference.provider ||
            !capabilities.supports(PaymentAdapterCapability.REFUNDS)
        ) {
            return RefundRequestDecision.Rejected(AdapterRejection.UNSUPPORTED_CAPABILITY)
        }
        if (request.amount.currency != payment.currency) {
            return RefundRequestDecision.Rejected(AdapterRejection.CURRENCY_MISMATCH)
        }
        if (refunds.any { it.id == request.refundId }) {
            return RefundRequestDecision.Rejected(AdapterRejection.ALREADY_RECORDED)
        }
        val existing = refunds.filter { it.paymentReference == payment.id }
        if (existing.any { it.currency != payment.currency }) {
            return RefundRequestDecision.Rejected(AdapterRejection.CURRENCY_MISMATCH)
        }
        val total = existing.fold(request.amount.amount) { amount, refund -> amount.add(refund.amount.amount) }
        if (total.compareTo(payment.amount.amount) > 0) {
            return RefundRequestDecision.Rejected(AdapterRejection.EXCEEDS_REFUNDABLE)
        }
        if (request.amount.amount.compareTo(payment.amount.amount) < 0 &&
            !capabilities.supports(PaymentAdapterCapability.PARTIAL_REFUNDS)
        ) {
            return RefundRequestDecision.Rejected(AdapterRejection.UNSUPPORTED_CAPABILITY)
        }
        return RefundRequestDecision.Accepted(request)
    }

    /** Checks a success against the request and returns the immutable refund fact and event receipt. */
    fun assessRefundSuccess(
        request: RequestRefund,
        observation: RefundSucceeded,
        payment: PaymentRecord,
        refunds: Collection<RefundRecord>,
        receipts: Collection<ProviderEventReceipt>,
        receivedAt: Instant,
    ): ProviderEventDecision<RefundRecord> {
        val target = ProviderEventTarget.Refund(observation.refundId, observation.paymentId)
        priorEvent(observation.event, target, receipts)?.let { return it }
        if (observation.refundId != request.refundId) {
            return ProviderEventDecision.Rejected(AdapterRejection.WRONG_REFUND)
        }
        if (observation.paymentId != request.paymentId) {
            return ProviderEventDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
        }
        if (observation.event.provider.value != request.providerReference.provider) {
            return ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (observation.amount.currency != request.amount.currency) {
            return ProviderEventDecision.Rejected(AdapterRejection.CURRENCY_MISMATCH)
        }
        if (observation.amount.amount.compareTo(request.amount.amount) != 0) {
            return ProviderEventDecision.Rejected(AdapterRejection.AMOUNT_MISMATCH)
        }
        if (observation.providerReference.provider != request.providerReference.provider ||
            refunds.any { it.externalReference == observation.providerReference }
        ) {
            return ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (request.paymentId != payment.id) {
            return ProviderEventDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
        }
        if (payment.externalReference != request.providerReference) {
            return ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (request.amount.currency != payment.currency ||
            refunds.any { it.paymentReference == payment.id && it.currency != payment.currency }
        ) {
            return ProviderEventDecision.Rejected(AdapterRejection.CURRENCY_MISMATCH)
        }
        if (refunds.any { it.id == request.refundId }) {
            return ProviderEventDecision.Rejected(AdapterRejection.ALREADY_RECORDED)
        }
        val total =
            refunds
                .filter { it.paymentReference == payment.id }
                .fold(request.amount.amount) { amount, refund -> amount.add(refund.amount.amount) }
        if (total.compareTo(payment.amount.amount) > 0) {
            return ProviderEventDecision.Rejected(AdapterRejection.EXCEEDS_REFUNDABLE)
        }
        return ProviderEventDecision.Accepted(
            RefundRecord.create(
                request.refundId,
                payment,
                observation.amount,
                observation.method,
                observation.occurredAt,
                observation.providerReference,
            ),
            ProviderEventReceipt(observation.event, target, observation.occurredAt, receivedAt),
        )
    }

    /** Failure records no refund; the application may record its receipt with its own request status. */
    fun assessRefundFailure(
        request: RequestRefund,
        observation: RefundFailed,
        refunds: Collection<RefundRecord>,
        receipts: Collection<ProviderEventReceipt>,
        receivedAt: Instant,
    ): ProviderEventDecision<Unit> {
        val target = ProviderEventTarget.Refund(observation.refundId, observation.paymentId)
        priorEvent(observation.event, target, receipts)?.let { return it }
        if (observation.refundId != request.refundId) {
            return ProviderEventDecision.Rejected(AdapterRejection.WRONG_REFUND)
        }
        if (observation.paymentId != request.paymentId) {
            return ProviderEventDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
        }
        if (observation.event.provider.value != request.providerReference.provider ||
            observation.providerReference?.provider != null &&
            observation.providerReference.provider != request.providerReference.provider
        ) {
            return ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
        }
        if (refunds.any { it.id == request.refundId }) {
            return ProviderEventDecision.Rejected(AdapterRejection.ALREADY_RECORDED)
        }
        return ProviderEventDecision.Accepted(
            Unit,
            ProviderEventReceipt(observation.event, target, observation.occurredAt, receivedAt),
        )
    }

    private fun priorEvent(
        event: ProviderEventReference,
        target: ProviderEventTarget,
        receipts: Collection<ProviderEventReceipt>,
    ): ProviderEventDecision<Nothing>? {
        val prior = receipts.firstOrNull { it.event == event } ?: return null
        return if (prior.target == target) {
            ProviderEventDecision.AlreadyProcessed
        } else {
            ProviderEventDecision.Rejected(AdapterRejection.EVENT_CONFLICT)
        }
    }
}
