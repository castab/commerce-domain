package io.github.castab.commerce.financial

import java.math.BigDecimal
import java.util.Currency

/**
 * An exact monetary amount in one [currency].
 *
 * Amounts are [BigDecimal] and keep their full precision. Arithmetic never rounds and never
 * converts between currencies: combining two amounts in different currencies is rejected
 * rather than producing a meaningless result. Scale and rounding for presentation or
 * settlement belong to the application.
 *
 * Amounts may be negative, for example a discount or credit line on a document.
 *
 * Equality is structural and follows [BigDecimal.equals], which is scale-sensitive:
 * `10.0 USD` and `10.00 USD` are numerically equal but not `==`. Use
 * [BigDecimal.compareTo] on [amount] when numeric comparison is intended.
 */
data class Money(
    val amount: BigDecimal,
    val currency: Currency,
) {

    /**
     * The exact sum of this amount and [other].
     *
     * @throws IllegalArgumentException if [other] is in a different currency.
     */
    operator fun plus(other: Money): Money {
        require(other.currency == currency) {
            "Cannot add ${other.currency.currencyCode} to ${currency.currencyCode}: currencies must match"
        }
        return Money(amount.add(other.amount), currency)
    }

    /**
     * The exact difference of this amount and [other]. The result may be negative.
     *
     * @throws IllegalArgumentException if [other] is in a different currency.
     */
    operator fun minus(other: Money): Money {
        require(other.currency == currency) {
            "Cannot subtract ${other.currency.currencyCode} from ${currency.currencyCode}: currencies must match"
        }
        return Money(amount.subtract(other.amount), currency)
    }

    /** The exact product of this amount and [multiplier], in the same currency. */
    operator fun times(multiplier: BigDecimal): Money = Money(amount.multiply(multiplier), currency)

    override fun toString(): String = "${amount.toPlainString()} ${currency.currencyCode}"

    companion object {

        /** A zero amount in [currency]. */
        @JvmStatic
        fun zero(currency: Currency): Money = Money(BigDecimal.ZERO, currency)
    }
}
