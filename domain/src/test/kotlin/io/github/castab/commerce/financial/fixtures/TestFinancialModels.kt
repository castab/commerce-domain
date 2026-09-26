package io.github.castab.commerce.financial.fixtures

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentHistory
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/*
 * Test-only helpers for the financial-document domain. Line items and money are real
 * immutable values; they are never mocked.
 */

val USD: Currency = Currency.getInstance("USD")
val EUR: Currency = Currency.getInstance("EUR")

fun usd(amount: String): Money = Money(BigDecimal(amount), USD)

fun eur(amount: String): Money = Money(BigDecimal(amount), EUR)

fun lineItem(
    description: String,
    quantity: String?,
    price: Money,
    taxAmount: Money,
    id: UUID = UUID.randomUUID(),
    subDescription: String? = null,
): LineItem =
    LineItem(
        id = id,
        description = description,
        subDescription = subDescription,
        quantity = quantity?.let(::BigDecimal),
        price = price,
        taxAmount = taxAmount,
    )

/**
 * An application-style in-memory [FinancialDocumentHistory]: one stored snapshot per
 * `(id, version)`, rejecting a second write of the same key the way a uniqueness
 * constraint in a real store would.
 */
class InMemoryFinancialDocumentHistory : FinancialDocumentHistory {
    private val snapshots = mutableMapOf<FinancialDocumentReference, FinancialDocument>()

    fun save(document: FinancialDocument) {
        check(snapshots.putIfAbsent(document.reference, document) == null) {
            "${document.reference} was already stored"
        }
    }

    override fun retrieveVersion(reference: FinancialDocumentReference): FinancialDocument? = snapshots[reference]

    override fun retrieveLatestVersion(id: UUID): FinancialDocument? = snapshots.values.filter { it.id == id }.maxByOrNull { it.version }
}
