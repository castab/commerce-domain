package io.github.castab.commerce.financial

import io.github.castab.commerce.financial.fixtures.USD
import io.github.castab.commerce.financial.fixtures.eur
import io.github.castab.commerce.financial.fixtures.lineItem
import io.github.castab.commerce.financial.fixtures.usd
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal

class LineItemSpec : FunSpec({

    test("with a quantity, the subtotal is price × quantity and the total adds tax") {
        val item = lineItem("Folding chairs", quantity = "3", price = usd("10.00"), taxAmount = usd("2.40"))

        item.subtotal shouldBe usd("30.00")
        item.total shouldBe usd("32.40")
    }

    test("the quantity may be fractional") {
        val item = lineItem("Brisket (lb)", quantity = "2.5", price = usd("18.00"), taxAmount = usd("0"))

        item.subtotal shouldBe usd("45.000")
    }

    test("without a quantity, the line is flat-priced and the subtotal is the price") {
        val item = lineItem("Event service fee", quantity = null, price = usd("100"), taxAmount = usd("8"))

        item.quantity shouldBe null
        item.subtotal shouldBe usd("100")
        item.total shouldBe usd("108")
    }

    test("taxAmount is a final amount, never applied as a rate") {
        val item = lineItem("Linens", quantity = "4", price = usd("25"), taxAmount = usd("0.10"))

        item.subtotal shouldBe usd("100")
        item.taxAmount shouldBe usd("0.10")
        item.total shouldBe usd("100.10")
    }

    test("the line currency is the currency of price and tax") {
        lineItem("Fee", quantity = null, price = usd("5"), taxAmount = usd("0")).currency shouldBe USD
    }

    test("price and tax in different currencies are rejected") {
        shouldThrow<IllegalArgumentException> {
            lineItem("Fee", quantity = null, price = usd("5"), taxAmount = eur("1"))
        }.message shouldContain "currencies must match"
    }

    test("a blank description is rejected") {
        shouldThrow<IllegalArgumentException> {
            lineItem(" ", quantity = null, price = usd("5"), taxAmount = usd("0"))
        }
    }

    test("copy() produces a validated, recalculated revision and leaves the original unchanged") {
        val original = lineItem("Tables", quantity = "2", price = usd("40"), taxAmount = usd("6.40"))
        val revised = original.copy(quantity = BigDecimal("3"), taxAmount = usd("9.60"))

        revised.id shouldBe original.id
        revised.subtotal shouldBe usd("120")
        revised.total shouldBe usd("129.60")
        original.subtotal shouldBe usd("80")

        shouldThrow<IllegalArgumentException> { original.copy(taxAmount = eur("1")) }
    }
})
