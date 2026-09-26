package io.github.castab.commerce.payment.adapter

import io.github.castab.commerce.financial.fixtures.usd
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.ExternalRefundReference
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.payment.RefundRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI
import java.time.Instant
import java.util.UUID

class PaymentAdapterContractSpec :
    FunSpec({
        val provider = PaymentProviderId("provider-a")
        val anotherProvider = PaymentProviderId("provider-b")
        val paymentId = UUID.randomUUID()
        val refundId = UUID.randomUUID()
        val time = Instant.parse("2026-09-25T12:00:00Z")
        val receivedAt = time.plusSeconds(5)
        val paymentReference = ExternalPaymentReference(provider.value, "payment-object")
        val refundReference = ExternalRefundReference(provider.value, "refund-object")
        val authorized = AuthorizedPayment(paymentId, provider, usd("500.00"))
        val preparation = PreparePayment(authorized, "Service")
        val capabilities =
            PaymentAdapterCapabilities(
                provider,
                setOf(
                    PaymentAdapterCapability.PAYMENT_INITIATION,
                    PaymentAdapterCapability.HOSTED_CHECKOUT,
                    PaymentAdapterCapability.REFUNDS,
                    PaymentAdapterCapability.PARTIAL_REFUNDS,
                ),
            )

        fun event(id: String = "event-1") = ProviderEventReference(provider, id)

        fun success(
            id: UUID = paymentId,
            reference: ExternalPaymentReference = paymentReference,
            amount: io.github.castab.commerce.financial.Money = usd("500"),
            eventId: String = "event-1",
        ) = PaymentSucceeded(id, reference, event(eventId), amount, PaymentMethod.CARD, time)

        fun recordedPayment() = PaymentRecord(paymentId, usd("500"), PaymentMethod.CARD, time, paymentReference)

        fun refundRequest(amount: io.github.castab.commerce.financial.Money = usd("100")) =
            RequestRefund(refundId, paymentId, paymentReference, amount)

        fun refundSuccess(
            amount: io.github.castab.commerce.financial.Money = usd("100"),
            eventId: String = "refund-event",
            id: UUID = refundId,
            reference: ExternalRefundReference = refundReference,
        ) = RefundSucceeded(id, paymentId, reference, event(eventId), amount, PaymentMethod.CARD, time)

        context("provider identities and immutable values") {
            test("arbitrary provider names are accepted and identities compare by provider and event id") {
                PaymentProviderId("cash-app").value shouldBe "cash-app"
                PaymentProviderId("manual rail").value shouldBe "manual rail"
                event() shouldBe ProviderEventReference(PaymentProviderId("provider-a"), "event-1")
                (event() == ProviderEventReference(anotherProvider, "event-1")) shouldBe false
                (event() == event("event-2")) shouldBe false
                paymentReference shouldBe ExternalPaymentReference("provider-a", "payment-object")
                refundReference shouldBe ExternalRefundReference("provider-a", "refund-object")
            }

            test("blank or malformed identities and amounts are rejected") {
                shouldThrow<IllegalArgumentException> { PaymentProviderId("  ") }
                shouldThrow<IllegalArgumentException> { PaymentProviderId(" provider-a ") }
                shouldThrow<IllegalArgumentException> { PaymentProviderId("provider\n") }
                shouldThrow<IllegalArgumentException> { ProviderEventReference(provider, "\t") }
                shouldThrow<IllegalArgumentException> { AuthorizedPayment(paymentId, provider, usd("0.00")) }
                shouldThrow<IllegalArgumentException> { RequestRefund(refundId, paymentId, paymentReference, usd("-1")) }
                shouldThrow<IllegalArgumentException> { PreparePayment(authorized, " ") }
            }

            test("capabilities are copied and can describe an observation-only adapter") {
                val mutable = mutableSetOf(PaymentAdapterCapability.PAYMENT_INITIATION)
                val snapshot = PaymentAdapterCapabilities(provider, mutable)
                mutable.clear()
                snapshot.supports(PaymentAdapterCapability.PAYMENT_INITIATION) shouldBe true
                PaymentAdapterCapabilities(provider, emptySet()).supports(PaymentAdapterCapability.REFUNDS) shouldBe false
            }
        }

        context("payment preparation") {
            test("preserves application payment identity and provider reference with optional hosted checkout") {
                val prepared =
                    PaymentPrepared(
                        paymentId,
                        paymentReference,
                        URI("https://checkout.example.test/session"),
                        time.plusSeconds(600),
                    )
                PaymentAdapterProcessing.assessPreparation(preparation, prepared, capabilities) shouldBe
                    PreparationDecision.Accepted(prepared)
                preparation.payment.amount shouldBe usd("500.00")
                prepared.paymentId shouldBe paymentId
                prepared.providerReference shouldBe paymentReference
                prepared.checkoutUri shouldBe URI("https://checkout.example.test/session")
            }

            test("provider without hosted checkout can prepare a payment without navigation") {
                val simple =
                    PaymentAdapterCapabilities(provider, setOf(PaymentAdapterCapability.PAYMENT_INITIATION))
                val prepared = PaymentPrepared(paymentId, paymentReference)
                PaymentAdapterProcessing.assessPreparation(preparation, prepared, simple) shouldBe
                    PreparationDecision.Accepted(prepared)
                PaymentAdapterProcessing.assessPreparation(
                    preparation,
                    prepared.copy(checkoutUri = URI("https://checkout.example.test")),
                    simple,
                ) shouldBe PreparationDecision.Rejected(AdapterRejection.UNSUPPORTED_CAPABILITY)
            }

            test("unsupported initiation and mismatched identity are rejected") {
                PaymentAdapterProcessing.assessPreparation(
                    preparation,
                    PaymentPrepared(paymentId, paymentReference),
                    PaymentAdapterCapabilities(provider, emptySet()),
                ) shouldBe PreparationDecision.Rejected(AdapterRejection.UNSUPPORTED_CAPABILITY)
                PaymentAdapterProcessing.assessPreparation(
                    preparation,
                    PaymentPrepared(UUID.randomUUID(), paymentReference),
                    capabilities,
                ) shouldBe PreparationDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
                PaymentAdapterProcessing.assessPreparation(
                    preparation,
                    PaymentPrepared(paymentId, ExternalPaymentReference("provider-b", "payment-object")),
                    capabilities,
                ) shouldBe PreparationDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
            }
        }

        context("payment observations") {
            test("success creates a payment fact and separate receipt") {
                val decision =
                    PaymentAdapterProcessing.assessPaymentSuccess(authorized, success(), emptyList(), emptyList(), receivedAt)
                val accepted = decision as ProviderEventDecision.Accepted<PaymentRecord>
                accepted.effect.id shouldBe paymentId
                accepted.effect.amount shouldBe usd("500")
                accepted.effect.externalReference shouldBe paymentReference
                accepted.receipt.event shouldBe event()
                accepted.receipt.target shouldBe ProviderEventTarget.Payment(paymentId)
                accepted.receipt.receivedAt shouldBe receivedAt
            }

            test("different currency and amount are rejected numerically") {
                val eur =
                    io.github.castab.commerce.financial.Money(
                        java.math.BigDecimal("500"),
                        java.util.Currency.getInstance("EUR"),
                    )
                PaymentAdapterProcessing.assessPaymentSuccess(
                    authorized,
                    success(amount = eur),
                    emptyList(),
                    emptyList(),
                    receivedAt,
                ) shouldBe
                    ProviderEventDecision.Rejected(AdapterRejection.CURRENCY_MISMATCH)
                PaymentAdapterProcessing.assessPaymentSuccess(
                    authorized,
                    success(amount = usd("499.99")),
                    emptyList(),
                    emptyList(),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.AMOUNT_MISMATCH)
            }

            test("wrong payment identity and conflicting references are rejected") {
                PaymentAdapterProcessing.assessPaymentSuccess(
                    authorized,
                    success(id = UUID.randomUUID()),
                    emptyList(),
                    emptyList(),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
                val prior = PaymentRecord(UUID.randomUUID(), usd("500"), PaymentMethod.CARD, time, paymentReference)
                PaymentAdapterProcessing.assessPaymentSuccess(
                    authorized,
                    success(),
                    listOf(prior),
                    emptyList(),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
                PaymentAdapterProcessing.assessPaymentSuccess(
                    authorized,
                    success(),
                    emptyList(),
                    emptyList(),
                    receivedAt,
                    PaymentPrepared(paymentId, ExternalPaymentReference(provider.value, "different")),
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
            }

            test("duplicate event is idempotent and an already recorded payment is not recorded twice") {
                val accepted =
                    PaymentAdapterProcessing.assessPaymentSuccess(
                        authorized,
                        success(),
                        emptyList(),
                        emptyList(),
                        receivedAt,
                    ) as ProviderEventDecision.Accepted<PaymentRecord>
                PaymentAdapterProcessing.assessPaymentSuccess(
                    authorized,
                    success(),
                    listOf(accepted.effect),
                    listOf(accepted.receipt),
                    receivedAt,
                ) shouldBe ProviderEventDecision.AlreadyProcessed
                PaymentAdapterProcessing.assessPaymentSuccess(
                    authorized,
                    success(eventId = "event-2"),
                    listOf(accepted.effect),
                    listOf(accepted.receipt),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.ALREADY_RECORDED)
                PaymentAdapterProcessing.assessPaymentSuccess(
                    authorized,
                    success(id = UUID.randomUUID()),
                    emptyList(),
                    listOf(accepted.receipt),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.EVENT_CONFLICT)
            }

            test("failure creates only a receipt and cannot undo a completed payment") {
                val failure = PaymentFailed(paymentId, event("failed-event"), time, paymentReference)
                val accepted =
                    PaymentAdapterProcessing.assessPaymentFailure(
                        authorized,
                        failure,
                        emptyList(),
                        emptyList(),
                        receivedAt,
                    ) as ProviderEventDecision.Accepted<Unit>
                accepted.effect shouldBe Unit
                accepted.receipt.target shouldBe ProviderEventTarget.Payment(paymentId)
                PaymentAdapterProcessing.assessPaymentFailure(
                    authorized,
                    failure,
                    emptyList(),
                    listOf(accepted.receipt),
                    receivedAt,
                ) shouldBe ProviderEventDecision.AlreadyProcessed
                PaymentAdapterProcessing.assessPaymentFailure(
                    authorized,
                    failure,
                    listOf(recordedPayment()),
                    emptyList(),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.ALREADY_RECORDED)
                PaymentAdapterProcessing.assessPaymentFailure(
                    authorized,
                    failure,
                    emptyList(),
                    emptyList(),
                    receivedAt,
                    PaymentPrepared(paymentId, ExternalPaymentReference(provider.value, "different")),
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
            }
        }

        context("refunds") {
            test("partial refund is authorized and success correlates to the application refund") {
                val request = refundRequest()
                PaymentAdapterProcessing.assessRefundRequest(request, recordedPayment(), emptyList(), capabilities) shouldBe
                    RefundRequestDecision.Accepted(request)
                val accepted =
                    PaymentAdapterProcessing.assessRefundSuccess(
                        request,
                        refundSuccess(),
                        recordedPayment(),
                        emptyList(),
                        emptyList(),
                        receivedAt,
                    ) as ProviderEventDecision.Accepted<RefundRecord>
                accepted.effect.id shouldBe refundId
                accepted.effect.paymentReference shouldBe paymentId
                accepted.effect.externalReference shouldBe refundReference
                accepted.receipt.target shouldBe ProviderEventTarget.Refund(refundId, paymentId)
            }

            test("refund capability and partial refund capability are distinct") {
                val noRefunds = PaymentAdapterCapabilities(provider, emptySet())
                PaymentAdapterProcessing.assessRefundRequest(
                    refundRequest(),
                    recordedPayment(),
                    emptyList(),
                    noRefunds,
                ) shouldBe RefundRequestDecision.Rejected(AdapterRejection.UNSUPPORTED_CAPABILITY)
                val onlyFull = PaymentAdapterCapabilities(provider, setOf(PaymentAdapterCapability.REFUNDS))
                PaymentAdapterProcessing.assessRefundRequest(
                    refundRequest(),
                    recordedPayment(),
                    emptyList(),
                    onlyFull,
                ) shouldBe RefundRequestDecision.Rejected(AdapterRejection.UNSUPPORTED_CAPABILITY)
                PaymentAdapterProcessing.assessRefundRequest(
                    refundRequest(usd("500")),
                    recordedPayment(),
                    emptyList(),
                    onlyFull,
                ) shouldBe RefundRequestDecision.Accepted(refundRequest(usd("500")))
            }

            test("cumulative refunds cannot exceed payment and provider amount must match request") {
                val existing = RefundRecord.create(UUID.randomUUID(), recordedPayment(), usd("450"), PaymentMethod.CARD, time)
                PaymentAdapterProcessing.assessRefundRequest(
                    refundRequest(usd("100")),
                    recordedPayment(),
                    listOf(existing),
                    capabilities,
                ) shouldBe RefundRequestDecision.Rejected(AdapterRejection.EXCEEDS_REFUNDABLE)
                PaymentAdapterProcessing.assessRefundSuccess(
                    refundRequest(),
                    refundSuccess(amount = usd("99")),
                    recordedPayment(),
                    emptyList(),
                    emptyList(),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.AMOUNT_MISMATCH)
            }

            test("wrong payment, currency, and provider refund reference are rejected") {
                val wrongPayment = PaymentRecord(UUID.randomUUID(), usd("500"), PaymentMethod.CARD, time, paymentReference)
                PaymentAdapterProcessing.assessRefundRequest(
                    refundRequest(),
                    wrongPayment,
                    emptyList(),
                    capabilities,
                ) shouldBe RefundRequestDecision.Rejected(AdapterRejection.WRONG_PAYMENT)
                val eur =
                    io.github.castab.commerce.financial.Money(
                        java.math.BigDecimal("100"),
                        java.util.Currency.getInstance("EUR"),
                    )
                PaymentAdapterProcessing.assessRefundRequest(
                    refundRequest(eur),
                    recordedPayment(),
                    emptyList(),
                    capabilities,
                ) shouldBe RefundRequestDecision.Rejected(AdapterRejection.CURRENCY_MISMATCH)
                shouldThrow<IllegalArgumentException> {
                    refundSuccess(reference = ExternalRefundReference("provider-b", "refund-object"))
                }
                PaymentAdapterProcessing.assessRefundRequest(
                    refundRequest().copy(providerReference = ExternalPaymentReference("provider-b", "payment-object")),
                    recordedPayment(),
                    emptyList(),
                    capabilities,
                ) shouldBe RefundRequestDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
            }

            test("refund success rejects another refund identity, currency, or reused provider object") {
                PaymentAdapterProcessing.assessRefundSuccess(
                    refundRequest(),
                    refundSuccess(id = UUID.randomUUID()),
                    recordedPayment(),
                    emptyList(),
                    emptyList(),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.WRONG_REFUND)
                val eur =
                    io.github.castab.commerce.financial.Money(
                        java.math.BigDecimal("100"),
                        java.util.Currency.getInstance("EUR"),
                    )
                PaymentAdapterProcessing.assessRefundSuccess(
                    refundRequest(),
                    refundSuccess(amount = eur),
                    recordedPayment(),
                    emptyList(),
                    emptyList(),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.CURRENCY_MISMATCH)
                val recorded =
                    RefundRecord.create(
                        UUID.randomUUID(),
                        recordedPayment(),
                        usd("50"),
                        PaymentMethod.CARD,
                        time,
                        refundReference,
                    )
                PaymentAdapterProcessing.assessRefundSuccess(
                    refundRequest(),
                    refundSuccess(),
                    recordedPayment(),
                    listOf(recorded),
                    emptyList(),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.CONFLICTING_REFERENCE)
            }

            test("duplicate refund event and conflicting event reuse are detected") {
                val accepted =
                    PaymentAdapterProcessing.assessRefundSuccess(
                        refundRequest(),
                        refundSuccess(),
                        recordedPayment(),
                        emptyList(),
                        emptyList(),
                        receivedAt,
                    ) as ProviderEventDecision.Accepted<RefundRecord>
                PaymentAdapterProcessing.assessRefundSuccess(
                    refundRequest(),
                    refundSuccess(),
                    recordedPayment(),
                    listOf(accepted.effect),
                    listOf(accepted.receipt),
                    receivedAt,
                ) shouldBe ProviderEventDecision.AlreadyProcessed
                PaymentAdapterProcessing.assessRefundSuccess(
                    refundRequest(),
                    refundSuccess(id = UUID.randomUUID()),
                    recordedPayment(),
                    emptyList(),
                    listOf(accepted.receipt),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.EVENT_CONFLICT)
            }

            test("failure creates no refund record and cannot follow a completed refund") {
                val failed = RefundFailed(refundId, paymentId, event("refund-failed"), time)
                val accepted =
                    PaymentAdapterProcessing.assessRefundFailure(
                        refundRequest(),
                        failed,
                        emptyList(),
                        emptyList(),
                        receivedAt,
                    ) as ProviderEventDecision.Accepted<Unit>
                accepted.effect shouldBe Unit
                accepted.receipt.target shouldBe ProviderEventTarget.Refund(refundId, paymentId)
                val refund = RefundRecord.create(refundId, recordedPayment(), usd("100"), PaymentMethod.CARD, time)
                PaymentAdapterProcessing.assessRefundFailure(
                    refundRequest(),
                    failed,
                    listOf(refund),
                    emptyList(),
                    receivedAt,
                ) shouldBe ProviderEventDecision.Rejected(AdapterRejection.ALREADY_RECORDED)
            }
        }
    })
