package io.github.castab.commerce.runtime.customer

import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/**
 * Creates a customer identity. Customers are independent of bookings: point-of-sale and
 * booking-driven applications create them the same way.
 *
 * The domain never generates identifiers; this operation does, through [newCustomerId].
 */
class CreateCustomer(
    private val transactor: Transactor,
    private val customers: CustomerRepository,
    private val newCustomerId: () -> Customer.Id = { Customer.Id(UUID.randomUUID()) },
) {
    data class Command(
        val name: CustomerName,
        val email: EmailAddress,
    )

    operator fun invoke(command: Command): Customer =
        transactor.inTransaction { transaction ->
            Customer(newCustomerId(), command.name, command.email)
                .also { customers.insert(transaction, it) }
        }
}

/** Reads one customer, or fails with [CommerceFailure.NotFound]. */
class GetCustomer(
    private val transactor: Transactor,
    private val customers: CustomerRepository,
) {
    operator fun invoke(id: Customer.Id): Customer =
        transactor.inTransaction { transaction -> customers.find(transaction, id) }
            ?: throw CommerceFailure.NotFound("Customer ${id.value} was not found")
}
