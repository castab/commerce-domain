package io.github.castab.commerce.payment

/**
 * How value moved between the payer and the business: the instrument of a [PaymentRecord]
 * or a [RefundRecord].
 *
 * A method describes the kind of instrument, not who processed it. The processor or
 * provider is recorded separately, if at all, by an [ExternalPaymentReference] or
 * [ExternalRefundReference]. A card payment remains `CARD` regardless of its provider.
 * Cash and checks usually have no provider at all.
 *
 * The method is a descriptive fact. The library attaches no policy to it: in particular, a
 * refund's method may differ from the method of the payment it refunds.
 */
enum class PaymentMethod {
    /** Physical currency. */
    CASH,

    /** A paper check. */
    CHECK,

    /** A credit, debit, or prepaid card, in person or online. */
    CARD,

    /** A direct transfer between bank accounts, such as ACH, SEPA, or a wire. */
    BANK_TRANSFER,

    /** A wallet or account-based payment app, such as PayPal, Apple Pay, or Cash App. */
    DIGITAL_WALLET,

    /** Any other instrument. The application may record the details elsewhere. */
    OTHER,
}
