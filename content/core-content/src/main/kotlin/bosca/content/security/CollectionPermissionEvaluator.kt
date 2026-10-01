package bosca.content.security

import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

class CollectionPermissionEvaluator(
    override val service: CollectionService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<ICollection, UUID>() {

    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scope = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> ApiTokenScopes.COLLECTIONS_VIEW
            PermissionAction.EDIT -> ApiTokenScopes.COLLECTIONS_EDIT
            PermissionAction.DELETE -> ApiTokenScopes.COLLECTIONS_DELETE
            PermissionAction.MANAGE -> ApiTokenScopes.COLLECTIONS_MANAGE
            PermissionAction.EXECUTE -> ApiTokenScopes.JOBS_EXECUTE
            else -> return false
        }
        return groupEvaluator.hasScope(authentication, scope.name)
    }
}