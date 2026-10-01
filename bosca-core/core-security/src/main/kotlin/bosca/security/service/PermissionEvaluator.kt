package bosca.security.service

import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissibleEntity
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService

/**
 * Evaluates entity ACLs against the groups held by the authenticated [bosca.security.model.Principal].
 */
abstract class PermissionEvaluator<T : PermissibleEntity<ID>, ID> : SecurityEvaluator() {

    abstract val service: PermissionService<T, ID>

    suspend fun verifyAllowed(
        authentication: AuthenticationContext?,
        entity: T,
        action: PermissionAction
    ) {
        if (!isAllowed(authentication, entity, action)) {
            groupEvaluator.throwUnauthorized()
        }
    }

    suspend fun verifyContentAllowed(
        authentication: AuthenticationContext?,
        entity: T,
        action: PermissionAction
    ) {
        if (!isContentAllowed(authentication, entity, action)) {
            groupEvaluator.throwUnauthorized()
        }
    }

    suspend fun verifySupplementaryAllowed(
        authentication: AuthenticationContext?,
        entity: T,
        action: PermissionAction
    ) {
        if (!isSupplementaryAllowed(authentication, entity, action)) {
            groupEvaluator.throwUnauthorized()
        }
    }

    private fun internalActionCheck(authentication: AuthenticationContext?, entity: T, action: PermissionAction): Boolean {
        return action == PermissionAction.VIEW && entity.public && (entity.isPublished || entity.isAdvertised) ||
                action == PermissionAction.LIST && entity.publicList && (entity.isPublished || entity.isAdvertised) ||
                action == PermissionAction.EDIT && groupEvaluator.hasEditorGroup(authentication)
    }

    suspend fun isAllowed(
        authentication: AuthenticationContext?,
        entity: T,
        action: PermissionAction
    ): Boolean {
        return internalIsAllowed(authentication, entity, action) { authentication, entity ->
            internalActionCheck(authentication, entity, action)
        }
    }

    suspend fun isAllowed(
        authentication: AuthenticationContext?,
        entities: List<T>,
        action: PermissionAction
    ): List<Boolean> {
        return batchInternalIsAllowed(authentication, entities, action) { authentication, entity ->
            internalActionCheck(authentication, entity, action)
        }
    }

    /**
     * Evaluates multiple authenticated identities against the same [entity], loading that entity's
     * permissions once for the entire batch. Results correspond positionally to [authentications].
     */
    suspend fun isAllowed(
        authentications: List<AuthenticationContext>,
        entity: T,
        action: PermissionAction,
    ): List<Boolean> {
        val permissions = service.getPermissions(entity)
        return authentications.map { authentication ->
            isAllowed(authentication, entity, action, permissions) { context, candidate ->
                internalActionCheck(context, candidate, action)
            }
        }
    }

    suspend fun filterAllowed(
        authentication: AuthenticationContext?,
        entities: List<T>,
        action: PermissionAction
    ): List<T> {
        return batchInternalFilterAllowed(authentication, entities, action) { authentication, entity ->
            internalActionCheck(authentication, entity, action)
        }
    }

    suspend fun isSupplementaryAllowed(
        authentication: AuthenticationContext?,
        entity: T,
        action: PermissionAction
    ): Boolean {
        return internalIsAllowed(authentication, entity, action) { _, entity ->
            action == PermissionAction.VIEW && entity.publicSupplementary && entity.isPublished
        }
    }

    suspend fun isSupplementaryAllowed(authentication: AuthenticationContext?, entities: List<T>, action: PermissionAction): List<Boolean> {
        return batchInternalIsAllowed(authentication, entities, action) { _, entity ->
            action == PermissionAction.VIEW && entity.publicSupplementary && entity.isPublished
        }
    }

    suspend fun isContentAllowed(authentication: AuthenticationContext?, entity: T, action: PermissionAction): Boolean {
        @Suppress("UNCHECKED_CAST")
        return internalIsAllowed(authentication, entity, action) { _, entity ->
            action == PermissionAction.VIEW && entity.publicContent && entity.isPublished
        }
    }

    suspend fun isContentAllowed(authentication: AuthenticationContext?, entities: List<T>, action: PermissionAction): List<Boolean> {
        return batchInternalIsAllowed(authentication, entities, action) { _, entity ->
            action == PermissionAction.VIEW && entity.publicContent && entity.isPublished
        }
    }

    private suspend fun batchInternalIsAllowed(
        authentication: AuthenticationContext?,
        entities: List<T>,
        action: PermissionAction,
        evaluator: (authentication: AuthenticationContext?, T) -> Boolean
    ): List<Boolean> {
        val allowed = mutableListOf<Boolean>()
        val ids = entities.map { it.id }
        val batch = Batch<ID, List<EntityPermission>>(ids)
        service.addPermissionsToBatch(batch)
        batch.ensureNotNull(emptyList())
        val allPermissions = batch.getResults()
        for (i in entities.indices) {
            val permissions = allPermissions[i] ?: emptyList()
            val entity = entities[i]
            allowed.add(isAllowed(authentication, entity, action, permissions, evaluator))
        }
        return allowed
    }

    private suspend fun batchInternalFilterAllowed(
        authentication: AuthenticationContext?,
        entities: List<T>,
        action: PermissionAction,
        evaluator: (authentication: AuthenticationContext?, T) -> Boolean
    ): List<T> {
        val ids = entities.map { it.id }
        val batch = Batch<ID, List<EntityPermission>>(ids)
        service.addPermissionsToBatch(batch)
        batch.ensureNotNull(emptyList())
        val allPermissions = batch.getResults()
        return buildList(entities.size) {
            for (i in entities.indices) {
                val permissions = allPermissions[i] ?: emptyList()
                val entity = entities[i]
                if (isAllowed(authentication, entity, action, permissions, evaluator)) {
                    add(entity)
                }
            }
        }
    }

    private suspend fun internalIsAllowed(authentication: AuthenticationContext?, entity: T, action: PermissionAction, evaluator: (authentication: AuthenticationContext?, T) -> Boolean): Boolean {
        val allPermissions = service.getPermissions(entity)
        return isAllowed(authentication, entity, action, allPermissions, evaluator)
    }

    private suspend fun isAllowed(authentication: AuthenticationContext?, entity: T?, action: PermissionAction, permissions: List<EntityPermission>, evaluator: (authentication: AuthenticationContext?, T) -> Boolean): Boolean {
        if (entity == null) {
            return false
        }
        if (entity.isDeleted) {
            // Soft-deleted entities stay visible to service accounts and administrators
            // (hasSaGroup accepts both groups) so admin tooling can list and restore them;
            // everyone else sees them as gone.
            return groupEvaluator.hasSaGroup(authentication)
        }
        if (action == PermissionAction.LIST && entity.publicList && entity.isPublished) {
            return true
        }
        val evaluatorAllowed = evaluator(authentication, entity)
        if (evaluatorAllowed && (action == PermissionAction.VIEW || action == PermissionAction.LIST)) {
            return true
        }
        val principal = authentication?.principal()
        if (principal is ScopedAuthenticatedPrincipal && principal.scopes != null &&
            !groupEvaluator.hasSaGroup(authentication) && !hasRequiredScope(authentication, action)
        ) {
            return false
        }
        if (evaluatorAllowed) return true
        if (principal != null && permissions.any {
            if (action == PermissionAction.EDIT && it.action == PermissionAction.MANAGE && principal.hasGroup(it.groupId)) {
                return true
            }
            it.action == action && principal.hasGroup(it.groupId)
        }) {
            return true
        }
        if (authentication != null && hasRoleBasedAccess(authentication, action)) {
            return true
        }
        return service.isParentAllowed(authentication, entity, action)
    }

    /**
     * Intersects private entity access with API-token scopes, including role-based,
     * explicit and inherited permissions. Public reads remain independently accessible.
     * Subclasses map actions to the scopes owned by their domain.
     */
    protected open fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        if (authentication?.principal() !is ScopedAuthenticatedPrincipal) return true
        val scope = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> ApiTokenScopes.CONTENT_VIEW
            PermissionAction.EDIT -> ApiTokenScopes.CONTENT_EDIT
            PermissionAction.DELETE -> ApiTokenScopes.CONTENT_DELETE
            PermissionAction.MANAGE -> ApiTokenScopes.CONTENT_MANAGE
            PermissionAction.EXECUTE -> ApiTokenScopes.JOBS_EXECUTE
            PermissionAction.IMPERSONATE -> ApiTokenScopes.SECURITY_MANAGE
        }
        return groupEvaluator.hasScope(authentication, scope.name)
    }

    /**
     * Checks whether the authenticated principal has role-based access for the given action
     * via built-in group membership (sa, administrators, editors, managers).
     *
     * API-token scopes are checked before this method. Subclasses can override it to
     * enforce their domain's role rules in addition to the required scope.
     */
    protected open fun hasRoleBasedAccess(authentication: AuthenticationContext, action: PermissionAction): Boolean {
        val principal = authentication.principal() ?: return false
        if (principal.hasGroup("sa") || principal.hasGroup("administrators")) {
            return true
        }
        if ((action != PermissionAction.MANAGE && action != PermissionAction.EXECUTE && action != PermissionAction.IMPERSONATE) &&
            (principal.hasGroup("editors") || principal.hasGroup("managers"))
        ) {
            return true
        }
        return false
    }
}
