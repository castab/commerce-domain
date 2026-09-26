package io.github.castab.commerce.financial

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class VersionSpec :
    FunSpec({

        test("INITIAL is version 1") {
            Version.INITIAL.number shouldBe 1
            Version.of(1) shouldBe Version.INITIAL
        }

        test("next() is the following version and leaves the source unchanged") {
            val v1 = Version.INITIAL
            val v2 = v1.next()

            v2.number shouldBe 2
            v2.next().number shouldBe 3
            v1.number shouldBe 1
        }

        test("of() reconstructs a valid existing version") {
            Version.of(37).number shouldBe 37
            Version.of(37) shouldBe Version.of(37)
            Version.of(37).hashCode() shouldBe Version.of(37).hashCode()
            Version.of(37) shouldNotBe Version.of(38)
        }

        test("of(0) fails") {
            shouldThrow<IllegalArgumentException> { Version.of(0) }.message shouldContain "at least 1"
        }

        test("of(negative) fails") {
            shouldThrow<IllegalArgumentException> { Version.of(-1) }
            shouldThrow<IllegalArgumentException> { Version.of(Int.MIN_VALUE) }
        }

        test("next() never overflows into an invalid version") {
            shouldThrow<ArithmeticException> { Version.of(Int.MAX_VALUE).next() }
        }

        test("versions are ordered by number") {
            Version.of(2) shouldBeGreaterThan Version.INITIAL
            Version.of(9) shouldBeLessThan Version.of(10)
            listOf(Version.of(3), Version.INITIAL, Version.of(2)).sorted() shouldBe
                listOf(Version.INITIAL, Version.of(2), Version.of(3))
        }

        test("toString is readable") {
            Version.of(5).toString() shouldBe "v5"
        }
    })
