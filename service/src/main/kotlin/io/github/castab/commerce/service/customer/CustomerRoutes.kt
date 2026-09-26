package io.github.castab.commerce.service.customer

import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.service.application.validating
import io.github.castab.commerce.service.http.jsonBody
import kotlinx.serialization.Serializable
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.Path
import org.http4k.lens.uuid
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes

/** `POST /customers` request body. */
@Serializable
data class CreateCustomerRequest(
    val name: String,
    val email: String,
)

/** A customer as returned over HTTP. */
@Serializable
data class CustomerResponse(
    val id: String,
    val name: String,
    val email: String,
) {
    companion object {
        fun from(customer: Customer): CustomerResponse =
            CustomerResponse(
                id = customer.id.value.toString(),
                name = customer.name.value,
                email = customer.email.value,
            )
    }
}

private val createCustomerRequest = jsonBody(CreateCustomerRequest.serializer())
private val customerResponse = jsonBody(CustomerResponse.serializer())
private val customerId = Path.uuid().of("customerId")

/**
 * The customer endpoints. Each route only translates: request DTO to domain values, one
 * application operation, and the result to a response DTO. Orchestration, transactions,
 * and SQL stay in the operations and repositories.
 *
 * - `POST /customers` creates a customer: `201` with the customer and its `Location`.
 * - `GET /customers/{customerId}` reads one: `200`, or `404` `not_found`.
 */
fun customerRoutes(
    createCustomer: CreateCustomer,
    getCustomer: GetCustomer,
): RoutingHttpHandler =
    routes(
        "/customers" bind Method.POST to { request ->
            val body = createCustomerRequest(request)
            val command = validating { CreateCustomer.Command(CustomerName(body.name), EmailAddress(body.email)) }
            val customer = createCustomer(command)
            Response(Status.CREATED)
                .header("Location", "/customers/${customer.id.value}")
                .with(customerResponse of CustomerResponse.from(customer))
        },
        "/customers/{customerId}" bind Method.GET to { request ->
            val customer = getCustomer(Customer.Id(customerId(request)))
            Response(Status.OK).with(customerResponse of CustomerResponse.from(customer))
        },
    )
