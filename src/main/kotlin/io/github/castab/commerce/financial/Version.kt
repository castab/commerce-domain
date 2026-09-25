package io.github.castab.commerce.financial

/**
 * The position of one immutable [FinancialDocument] snapshot within its lineage.
 *
 * Versions start at [INITIAL] (1) and only ever grow by one: every change order and every
 * lifecycle transition produces a successor whose version is [next] of its source. A
 * version below 1 cannot exist.
 *
 * A version is a domain concept, not an identifier. The identity of a lineage is the
 * document's `UUID`. The pair of both identifies exactly one snapshot, see
 * [FinancialDocumentReference].
 *
 * Versions compare by their [number], so snapshots of one lineage can be ordered.
 */
class Version private constructor(
    /** The 1-based version number, suitable for storage in a persistence adapter. */
    val number: Int,
) : Comparable<Version> {
    /**
     * The version that immediately follows this one.
     *
     * @throws ArithmeticException if the version number would overflow [Int.MAX_VALUE].
     */
    fun next(): Version = Version(Math.addExact(number, 1))

    /** The version immediately preceding this one, or `null` for [INITIAL]. */
    @JvmSynthetic
    internal fun previous(): Version? = if (number == 1) null else Version(number - 1)

    override fun compareTo(other: Version): Int = number.compareTo(other.number)

    override fun equals(other: Any?): Boolean = other is Version && other.number == number

    override fun hashCode(): Int = number

    override fun toString(): String = "v$number"

    companion object {
        /** The version of every newly created financial-document lineage: 1. */
        @JvmField
        val INITIAL: Version = Version(1)

        /**
         * Reconstructs an existing version from its [number], for example when a persistence
         * adapter maps a stored row back into a [FinancialDocument].
         *
         * @throws IllegalArgumentException if [number] is less than 1.
         */
        @JvmStatic
        fun of(number: Int): Version {
            require(number >= 1) { "A version number must be at least 1, but was $number" }
            return if (number == 1) INITIAL else Version(number)
        }
    }
}
