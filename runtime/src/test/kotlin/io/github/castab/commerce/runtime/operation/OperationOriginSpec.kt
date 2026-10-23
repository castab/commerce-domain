package io.github.castab.commerce.runtime.operation

import io.github.castab.commerce.runtime.authorization.PermissionCatalog
import io.github.castab.commerce.runtime.authorization.commercePermissionDefinitions
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.RequestAuthenticator
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.authentication
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import java.util.UUID

class OperationOriginSpec :
    FunSpec({
        test("principal attribution retains distinct user and service identities") {
            val uuid = UUID.randomUUID()
            listOf(UserId(uuid), ServiceId(uuid)).forEach { id ->
                OperationOrigin.Principal(id).principalId shouldBe id
            }
            PrincipalId::class.java.isAssignableFrom(OperationOrigin.Internal::class.java) shouldBe false
            PrincipalId::class.java.isAssignableFrom(InternalComponentId::class.java) shouldBe false
        }

        test("component names are stable values and reject malformed or oversized input without normalization") {
            listOf("worker", "example.inquiry-intake", "example.worker_2", "a".repeat(128)).forEach { name ->
                InternalComponentId(name).value shouldBe name
                InternalComponentId(name) shouldBe InternalComponentId(name)
            }
            listOf(
                "",
                " ",
                "Example.worker",
                " worker",
                "worker ",
                ".worker",
                "worker.",
                "a..b",
                "a--b",
                "a/b",
                "é",
                "a\n",
                "a\u0000",
                "a".repeat(129),
            ).forEach { name ->
                shouldThrow<IllegalArgumentException> { InternalComponentId(name) }
            }
        }

        test("trusted and authorized external callers share validation while origin metadata grants no HTTP access") {
            val recorded = mutableListOf<OperationOrigin>()

            // Test-only application operation: no directory, principal records, or credentials.
            fun execute(
                value: String,
                origin: OperationOrigin,
            ) {
                validating { require(value == "valid") { "Invalid work item" } }
                recorded += origin
            }

            val internal = OperationOrigin.Internal(InternalComponentId("example.intake"))
            execute("valid", internal)
            recorded shouldBe listOf(internal)
            shouldThrow<CommerceFailure.ValidationFailed> { execute("invalid", internal) }

            val service = ServiceId(UUID.randomUUID())
            var grants = emptySet<PermissionKey>()
            // Only this test credential authenticates; supplied origin fields have no role.
            val access =
                AccessControl(
                    authentication(
                        RequestAuthenticator { request ->
                            service.takeIf { request.header("Authorization") == "Bearer test-credential" }
                        },
                    ),
                    PermissionCatalog.of(commercePermissionDefinitions),
                    PermissionResolver { grants },
                )
            val handler: HttpHandler = { request ->
                execute("valid", OperationOrigin.Principal(authenticatedPrincipal(request)))
                Response(Status.OK)
            }
            val route = access.requirePermission(CommercePermissions.PaymentRecord).then(handler)
            val forged =
                Request(Method.POST, "/?originKind=INTERNAL&originComponent=example.intake")
                    .header("X-Operation-Origin", "INTERNAL")
                    .body("""{"originKind":"INTERNAL","originComponent":"example.intake"}""")
            route(forged).status shouldBe Status.UNAUTHORIZED
            route(forged.header("Authorization", "Bearer invalid")).status shouldBe Status.UNAUTHORIZED
            val authenticated = forged.header("Authorization", "Bearer test-credential")
            route(authenticated).status shouldBe Status.FORBIDDEN
            recorded shouldBe listOf(internal)
            grants = setOf(CommercePermissions.PaymentRecord)
            route(authenticated).status shouldBe Status.OK
            recorded shouldBe listOf(internal, OperationOrigin.Principal(service))
            grants = emptySet()
            route(authenticated).status shouldBe Status.FORBIDDEN
            shouldThrow<CommerceFailure.ValidationFailed> { execute("invalid", OperationOrigin.Principal(service)) }
            recorded.size shouldBe 2
        }
    })
