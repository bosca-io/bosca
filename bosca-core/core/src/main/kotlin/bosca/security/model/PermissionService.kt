package bosca.security.model

import bosca.graphql.Batch
import bosca.security.service.AuthenticationContext
import bosca.service.Service

/**
 * Provides permission lookups for a specific [PermissibleEntity] type.
 *
 * Domain services that manage permissioned resources (e.g. collections, dashboards,
 * organizations) extend this interface alongside their own service contracts.
 *
 * @param T the permissible entity type
 * @param ID the entity's primary key type
 */
interface PermissionService<T : PermissibleEntity<ID>, ID> : Service {

    /**
     * Returns all permission grants for the given [entity].
     *
     * @param entity the entity to look up permissions for
     * @return the list of [EntityPermission] entries associated with this entity
     */
    suspend fun getPermissions(entity: T): List<EntityPermission>

    /**
     * Populates a GraphQL [Batch] with permission lists, enabling DataLoader-style
     * batched permission resolution across multiple entities in a single request.
     */
    suspend fun addPermissionsToBatch(batch: Batch<ID, List<EntityPermission>>)

    /**
     * Checks whether the user has [action] on a parent of [entity]. Used by
     * [bosca.security.service.PermissionEvaluator] to implement permission
     * cascade: a child inherits its parent's access unless the child has its
     * own explicit grants that broaden it. The default returns `false`
     * (no parent, no cascade) — entities with a parent override this to
     * delegate to the parent's [PermissionEvaluator.isAllowed].
     */
    suspend fun isParentAllowed(
        authentication: AuthenticationContext?,
        entity: T,
        action: PermissionAction,
    ): Boolean = false
}