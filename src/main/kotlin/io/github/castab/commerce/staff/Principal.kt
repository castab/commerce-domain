package io.github.castab.commerce.staff

/** The authenticated identity supplied to commerce authorization. */
sealed interface PrincipalId

/** Availability of a human or service principal for authorization. */
enum class PrincipalStatus {
    ACTIVE,
    DISABLED,
}

/** Source-compatible name for the status of a human [User]. */
typealias UserStatus = PrincipalStatus

/** A human or software entity that may be authorized through assigned roles. */
interface Principal {
    /** Stable identity of this human or service principal. */
    val id: PrincipalId

    /** Whether this principal can receive permissions through roles. */
    val status: PrincipalStatus

    /** References to roles assigned to this principal. */
    val roles: Set<RoleAssignment>
}
