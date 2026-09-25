package io.github.castab.commerce.financial.fixtures

import io.github.castab.commerce.customer.Customer
import java.util.UUID

/** A stable customer for financial and payment domain fixtures. */
val TEST_CUSTOMER_ID: Customer.Id = Customer.Id(UUID(0L, 1L))
