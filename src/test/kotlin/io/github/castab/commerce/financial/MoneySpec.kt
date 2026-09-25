package io.github.castab.commerce.financial

import io.github.castab.commerce.financial.fixtures.EUR
import io.github.castab.commerce.financial.fixtures.USD
import io.github.castab.commerce.financial.fixtures.eur
import io.github.castab.commerce.financial.fixtures.usd
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal

class MoneySpec : FunSpec({

    test("adds amounts in the same currency exactly") {
        usd("10.25") + usd("0.75") shouldBe usd("11.00")
        usd("0.1") + usd("0.2") shouldBe usd("0.3")
    }

    test("multiplies by a BigDecimal exactly, without rounding") {
        usd("25") * BigDecimal("4") shouldBe usd("100")
        usd("10.00") * BigDecimal("3") shouldBe usd("30.00")
        usd("0.333") * BigDecimal("0.5") shouldBe usd("0.1665")
    }

    test("zero is the identity for addition") {
        Money.zero(USD) + usd("12.34") shouldBe usd("12.34")
        Money.zero(EUR).currency shouldBe EUR
    }

    test("adding a different currency fails instead of converting") {
        shouldThrow<IllegalArgumentException> { usd("1") + eur("1") }.message shouldContain "EUR"
        shouldThrow<IllegalArgumentException> { Money.zero(EUR) + usd("1") }
    }

    test("subtracts amounts in the same currency exactly, without rounding") {
        usd("10.25") - usd("0.75") shouldBe usd("9.50")
        usd("0.3") - usd("0.1") shouldBe usd("0.2")
        usd("100") - usd("0.001") shouldBe usd("99.999")
    }

    test("subtraction may produce zero or a negative amount") {
        (usd("5.00") - usd("5.00")).amount.compareTo(BigDecimal.ZERO) shouldBe 0
        usd("5") - usd("7.50") shouldBe usd("-2.50")
    }

    test("subtracting a different currency fails instead of converting") {
        shouldThrow<IllegalArgumentException> { usd("1") - eur("1") }.message shouldContain "EUR"
        shouldThrow<IllegalArgumentException> { Money.zero(EUR) - usd("1") }
    }

    test("negative amounts are allowed, for discounts and credits") {
        usd("100") + usd("-15") shouldBe usd("85")
    }

    test("equality follows BigDecimal and is scale-sensitive") {
        usd("10.0") shouldNotBe usd("10.00")
        usd("10.0").amount.compareTo(usd("10.00").amount) shouldBe 0
        usd("10.00") shouldNotBe eur("10.00")
    }

    test("toString shows the plain amount and currency code") {
        usd("1E+2").toString() shouldBe "100 USD"
    }
})
