package io.github.castab.commerce.runtime.persistence

import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi

/**
 * One open PostgreSQL transaction.
 *
 * Only a [Transactor] creates one. Repositories take it as an explicit parameter and
 * never begin, commit, or roll back on their own, so every write an application
 * operation makes through the same [Transaction] commits or rolls back together.
 */
class Transaction internal constructor(
    /** The JDBI handle bound to this transaction, for repository implementations. */
    val handle: Handle,
)

/**
 * The explicit transaction boundary of operations.
 *
 * [inTransaction] commits when [block] returns and rolls back when it throws, rethrowing
 * the original exception. An operation that coordinates several persistent concepts, for
 * example recording a payment, its allocation, and a resulting booking transition, does
 * all of it inside one [inTransaction] call. Calling [inTransaction] again from inside
 * [block] opens a separate transaction; pass the existing [Transaction] instead.
 */
class Transactor(
    private val jdbi: Jdbi,
) {
    fun <T> inTransaction(block: (Transaction) -> T): T =
        jdbi.inTransaction<T, RuntimeException> { handle ->
            block(Transaction(handle))
        }
}
