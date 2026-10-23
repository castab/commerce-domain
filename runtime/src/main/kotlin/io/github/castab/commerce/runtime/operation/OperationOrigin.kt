package io.github.castab.commerce.runtime.operation

import io.github.castab.commerce.staff.PrincipalId

/**
 * Explicit attribution for an application operation, not proof of identity or authority.
 * Applications establish trust at their entry boundaries and own audit persistence.
 * Neither variant grants permissions or changes validation and transaction guarantees.
 * Never select trusted execution from a caller-supplied origin or component identifier.
 */
sealed interface OperationOrigin {
    /**
     * Attribution to a USER or SERVICE. At an external boundary, obtain [principalId]
     * from authentication and authorize the operation before invoking it. Constructing
     * this value does not authenticate the principal or check its current permissions.
     */
    data class Principal(
        val principalId: PrincipalId,
    ) : OperationOrigin

    /**
     * Attribution to an in-process component deliberately wired by the application.
     * Requires no principal registration, credential, session, or role. This value is
     * descriptive metadata, not a capability token or an authorization bypass.
     */
    data class Internal(
        val component: InternalComponentId,
    ) : OperationOrigin
}
