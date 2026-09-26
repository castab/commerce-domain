package io.github.castab.commerce.service.customer

import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.service.application.CommerceFailure
import io.github.castab.commerce.service.persistence.Transaction
import io.github.castab.commerce.service.persistence.isUniqueViolation
import org.jdbi.v3.core.statement.StatementException
import java.util.UUID

/**
 * PostgreSQL persistence of the domain's [Customer] in `commerce.customers`.
 *
 * Every method runs inside the caller's [Transaction]; the repository never opens or
 * commits one. It stores exactly what the domain type holds: identity, name, and email.
 */
class CustomerRepository {
    /** Inserts [customer]; an existing id is reported as [CommerceFailure.Conflict]. */
    fun insert(
        transaction: Transaction,
        customer: Customer,
    ) {
        try {
            transaction.handle
                .createUpdate("INSERT INTO commerce.customers (id, name, email) VALUES (:id, :name, :email)")
                .bind("id", customer.id.value)
                .bind("name", customer.name.value)
                .bind("email", customer.email.value)
                .execute()
        } catch (e: StatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Customer ${customer.id.value} already exists", e)
            throw e
        }
    }

    fun find(
        transaction: Transaction,
        id: Customer.Id,
    ): Customer? =
        transaction.handle
            .createQuery("SELECT id, name, email FROM commerce.customers WHERE id = :id")
            .bind("id", id.value)
            .map { row, _ ->
                Customer(
                    id = Customer.Id(row.getObject("id", UUID::class.java)),
                    name = CustomerName(row.getString("name")),
                    email = EmailAddress(row.getString("email")),
                )
            }.findOne()
            .orElse(null)
}
