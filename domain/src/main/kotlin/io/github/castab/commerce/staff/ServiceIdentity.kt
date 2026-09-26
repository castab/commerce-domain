package io.github.castab.commerce.staff

import java.util.Collections
import java.util.UUID

/** Stable identity of an authenticated software caller, distinct from [UserId]. */
@JvmInline
value class ServiceId(
    val value: UUID,
) : PrincipalId

/**
 * A non-human principal such as an adapter or worker. Authentication credentials remain
 * outside this model. Role assignments are copied on creation.
 */
class ServiceIdentity(
    override val id: ServiceId,
    val name: String,
    override val status: PrincipalStatus,
    roles: Set<RoleAssignment>,
) : Principal {
    override val roles: Set<RoleAssignment> = Collections.unmodifiableSet(LinkedHashSet(roles))

    init {
        require(name.isNotBlank()) { "Service name must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ServiceIdentity &&
            id == other.id &&
            name == other.name &&
            status == other.status &&
            roles == other.roles

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + roles.hashCode()
        return result
    }

    override fun toString(): String = "ServiceIdentity(id=$id, name=$name, status=$status, roles=$roles)"
}
