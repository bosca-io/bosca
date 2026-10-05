package bosca.forms.security

import bosca.forms.model.FormSchema
import bosca.forms.service.FormSchemaService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/**
 * Evaluates whether an authenticated user has permission to perform
 * an action on a form schema, using the same group-based permission
 * model as metadata, collections, and profiles.
 */
class FormSchemaPermissionEvaluator(
    override val service: FormSchemaService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator,
) : PermissionEvaluator<FormSchema, UUID>() {

    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scope = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> ApiTokenScopes.FORMS_READ
            PermissionAction.EDIT -> ApiTokenScopes.FORMS_EDIT
            PermissionAction.DELETE, PermissionAction.MANAGE -> ApiTokenScopes.FORMS_MANAGE
            PermissionAction.EXECUTE -> ApiTokenScopes.FORMS_SUBMIT
            else -> return false
        }
        return groupEvaluator.hasScope(authentication, scope.name)
    }
}
