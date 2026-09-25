package io.github.castab.commerce.staff

import java.util.Collections
import java.util.UUID

/** Stable identity of a human staff member. Authentication is owned by the application. */
@JvmInline
value class UserId(
    val value: UUID,
) : PrincipalId

/** A reference to a role definition, without embedding its permissions in a user. */
data class RoleAssignment(
    val role: RoleKey,
)

/**
 * A human staff member known to a commerce application, without login credentials or
 * session data. Role assignments are copied so later caller mutations cannot change this
 * value. A [PrincipalStatus.DISABLED] user receives no permissions.
 */
class User(
    override val id: UserId,
    val username: String,
    val firstName: String?,
    val lastName: String?,
    val displayName: String,
    override val status: PrincipalStatus,
    roles: Set<RoleAssignment>,
) : Principal {
    override val roles: Set<RoleAssignment> = Collections.unmodifiableSet(LinkedHashSet(roles))

    init {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(displayName.isNotBlank()) { "User display name must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is User &&
            id == other.id &&
            username == other.username &&
            firstName == other.firstName &&
            lastName == other.lastName &&
            displayName == other.displayName &&
            status == other.status &&
            roles == other.roles

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + (firstName?.hashCode() ?: 0)
        result = 31 * result + (lastName?.hashCode() ?: 0)
        result = 31 * result + displayName.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + roles.hashCode()
        return result
    }

    override fun toString(): String =
        "User(id=$id, username=$username, firstName=$firstName, lastName=$lastName, " +
            "displayName=$displayName, status=$status, roles=$roles)"
}
