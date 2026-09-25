package io.github.castab.commerce.payment

import io.github.castab.commerce.financial.fixtures.eur
import io.github.castab.commerce.financial.fixtures.usd
import io.github.castab.commerce.payment.fixtures.minutesLater
import io.github.castab.commerce.payment.fixtures.payment
import io.github.castab.commerce.payment.fixtures.shouldBeNumerically
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class RefundRecordSpec :
    FunSpec({

        val payment =
            payment(
                usd("500.00"),
                PaymentMethod.CARD,
                externalReference = ExternalPaymentReference("stripe", "pi_123"),
            )

        test("references the original payment by its UUID, not a financial document") {
            val id = UUID.randomUUID()
            val refund = RefundRecord.create(id, payment, usd("100.00"), PaymentMethod.CARD, minutesLater(60))

            refund.id shouldBe id
            refund.paymentReference shouldBe payment.id
            refund.amount shouldBe usd("100.00")
            refund.method shouldBe PaymentMethod.CARD
            refund.refundedAt shouldBe minutesLater(60)
            RefundRecord::class.java.declaredFields
                .map { it.type.simpleName }
                .toSet() shouldBe
                setOf("UUID", "Money", "PaymentMethod", "Instant", "ExternalRefundReference", "Companion")
        }

        test("a positive refund is accepted, up to the full payment amount") {
            RefundRecord
                .create(UUID.randomUUID(), payment, usd("0.01"), PaymentMethod.CARD, minutesLater(1))
                .amount shouldBe usd("0.01")
            RefundRecord
                .create(UUID.randomUUID(), payment, usd("500"), PaymentMethod.CARD, minutesLater(1))
                .amount shouldBe usd("500")
        }

        test("a zero or negative refund is rejected") {
            listOf("0", "0.00", "-1").forEach { amount ->
                shouldThrow<IllegalArgumentException> {
                    RefundRecord.create(UUID.randomUUID(), payment, usd(amount), PaymentMethod.CARD, minutesLater(1))
                }.message shouldContain "positive"
                shouldThrow<IllegalArgumentException> {
                    RefundRecord.restore(UUID.randomUUID(), payment.id, usd(amount), PaymentMethod.CARD, minutesLater(1))
                }.message shouldContain "positive"
            }
        }

        test("the refund currency must match the payment currency") {
            shouldThrow<IllegalArgumentException> {
                RefundRecord.create(UUID.randomUUID(), payment, eur("100.00"), PaymentMethod.CARD, minutesLater(1))
            }.message shouldContain "currencies must match"
        }

        test("a single refund cannot exceed the payment") {
            shouldThrow<IllegalArgumentException> {
                RefundRecord.create(UUID.randomUUID(), payment, usd("500.01"), PaymentMethod.CARD, minutesLater(1))
            }.message shouldContain "exceeds the amount of payment"
        }

        test("a partial refund is supported") {
            val refund = RefundRecord.create(UUID.randomUUID(), payment, usd("150.00"), PaymentMethod.CARD, minutesLater(1))

            val reconciliation = PaymentReconciliation.reconcile(payment, emptyList(), refunds = listOf(refund))

            reconciliation.totalRefunded shouldBeNumerically usd("150.00")
            reconciliation.netReceived shouldBeNumerically usd("350.00")
            reconciliation.unallocated shouldBeNumerically usd("350.00")
        }

        test("multiple partial refunds are supported while their total stays within the payment") {
            val refunds =
                listOf("200.00", "200.00", "100.00").map { amount ->
                    RefundRecord.create(UUID.randomUUID(), payment, usd(amount), PaymentMethod.CARD, minutesLater(1))
                }

            val reconciliation = PaymentReconciliation.reconcile(payment, emptyList(), refunds = refunds)

            reconciliation.totalRefunded shouldBeNumerically usd("500.00")
            reconciliation.netReceived shouldBeNumerically usd("0")
            reconciliation.unallocated shouldBeNumerically usd("0")
        }

        test("cumulative refunds above the payment are rejected during reconciliation") {
            val refunds =
                listOf("300.00", "200.01").map { amount ->
                    RefundRecord.create(UUID.randomUUID(), payment, usd(amount), PaymentMethod.CARD, minutesLater(1))
                }

            shouldThrow<IllegalArgumentException> {
                PaymentReconciliation.reconcile(payment, emptyList(), refunds = refunds)
            }.message shouldContain "Refunds of payment ${payment.id} total 500.01 USD, which exceeds the payment amount"
        }

        test("the refund method may differ from the payment method") {
            val check = payment(usd("80.00"), PaymentMethod.CHECK)
            val cashRefund = RefundRecord.create(UUID.randomUUID(), check, usd("80.00"), PaymentMethod.CASH, minutesLater(1))
            cashRefund.method shouldBe PaymentMethod.CASH

            val debit = payment(usd("25.00"), PaymentMethod.CARD)
            RefundRecord
                .create(UUID.randomUUID(), debit, usd("25.00"), PaymentMethod.CASH, minutesLater(1))
                .method shouldBe PaymentMethod.CASH

            PaymentReconciliation
                .reconcile(check, emptyList(), refunds = listOf(cashRefund))
                .totalRefunded shouldBeNumerically usd("80.00")
        }

        test("the external refund reference is optional") {
            val cash = RefundRecord.create(UUID.randomUUID(), payment, usd("10"), PaymentMethod.CASH, minutesLater(1))
            cash.externalReference.shouldBeNull()

            val processed =
                RefundRecord.create(
                    UUID.randomUUID(),
                    payment,
                    usd("10"),
                    PaymentMethod.CARD,
                    minutesLater(1),
                    ExternalRefundReference("stripe", "re_456"),
                )
            processed.externalReference shouldBe ExternalRefundReference("stripe", "re_456")
        }

        test("a blank external refund provider or reference is rejected") {
            shouldThrow<IllegalArgumentException> { ExternalRefundReference(" ", "re_456") }.message shouldContain "provider"
            shouldThrow<IllegalArgumentException> { ExternalRefundReference("stripe", "") }.message shouldContain "reference"
        }

        test("a refund does not modify the payment") {
            val before = PaymentRecord(payment.id, payment.amount, payment.method, payment.receivedAt, payment.externalReference)
            RefundRecord.create(UUID.randomUUID(), payment, usd("100.00"), PaymentMethod.CASH, minutesLater(1))
            payment shouldBe before
        }

        test("restore rebuilds an equal refund") {
            val created =
                RefundRecord.create(
                    UUID.randomUUID(),
                    payment,
                    usd("42.00"),
                    PaymentMethod.CARD,
                    minutesLater(1),
                    ExternalRefundReference("stripe", "re_1"),
                )
            RefundRecord.restore(
                created.id,
                created.paymentReference,
                created.amount,
                created.method,
                created.refundedAt,
                created.externalReference,
            ) shouldBe created
        }
    })
