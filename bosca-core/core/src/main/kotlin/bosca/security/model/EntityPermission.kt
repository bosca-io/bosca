package bosca.security.model

import bosca.serialization.UUID

/**
 * Represents a single permission grant linking an entity to a security group
 * with a specific [PermissionAction] (e.g. read, write, admin).
 */
interface EntityPermission {

    /** The ID of the entity (collection, metadata, dashboard, etc.) this permission applies to. */
    val entityId: UUID

    /** The security group that is granted the permission. */
    val groupId: UUID

    /** The action that the group is permitted to perform on the entity. */
    val action: PermissionAction
}