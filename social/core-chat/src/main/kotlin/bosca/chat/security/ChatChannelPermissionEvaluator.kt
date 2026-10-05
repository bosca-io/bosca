package bosca.chat.security

import bosca.chat.model.ChatChannel
import bosca.chat.service.ChatService
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.PermissionEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/**
 * Evaluates entity-level permissions for [ChatChannel] access by delegating
 * to the base [PermissionEvaluator] framework with channel-specific permission
 * records and group membership checks. This authorizes the principal, not an
 * acting profile; profile-scoped mutations must separately require the acting
 * profile to be a current channel member.
 */
class ChatChannelPermissionEvaluator(
    override val service: ChatService,
    override val securityService: SecurityService,
    override val groupEvaluator: GroupEvaluator
) : PermissionEvaluator<ChatChannel, UUID>() {

    override fun hasRequiredScope(authentication: AuthenticationContext?, action: PermissionAction): Boolean {
        val scope = when (action) {
            PermissionAction.VIEW, PermissionAction.LIST -> ApiTokenScopes.MESSAGES_READ
            PermissionAction.EDIT, PermissionAction.DELETE, PermissionAction.EXECUTE -> ApiTokenScopes.MESSAGES_EDIT
            PermissionAction.MANAGE -> ApiTokenScopes.SECURITY_MANAGE
            else -> return false
        }
        return groupEvaluator.hasScope(authentication, scope.name)
    }
}
