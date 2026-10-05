package bosca.security.graphql

import bosca.db.transaction
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.model.CredentialType
import bosca.security.model.SimplePasswordAttributes
import bosca.security.routes.security.PASSWORD_LENGTH_RANGE
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService

object PrincipalMutation

@TypeController
class PrincipalMutationController(
    private val securityService: SecurityService,
    private val groupEvaluator: GroupEvaluator,
    private val profileService: ProfileService
) : GraphQLController<PrincipalMutation> {

    @Field
    suspend fun identifier(
        authentication: AuthenticationContext,
        identifier: String,
        password: String
    ): Boolean = transaction {
        val principal = authentication.principal()?.asPrincipal() ?: throw SecurityException("Unauthorized access")
        val principalObj = securityService.getPrincipalById(principal.id) ?: throw SecurityException("Unauthorized access")
        val credentials = securityService.getCredentials(principalObj).filter { it.type.isPasswordCredential() }
        val credential = credentials.firstOrNull() ?: throw SecurityException("Unauthorized access")
        securityService.loginWithCredential(SimplePasswordAttributes(credential.attributes.identifier, password), generateRefreshToken = false)
        securityService.updateIdentifier(principal.id, identifier)
        true
    }

    @Field
    suspend fun password(
        authentication: AuthenticationContext,
        identifier: String?,
        newPassword: String,
        oldPassword: String
    ): Boolean = transaction {
        require(newPassword.length in PASSWORD_LENGTH_RANGE) { "Password must be between ${PASSWORD_LENGTH_RANGE.first} and ${PASSWORD_LENGTH_RANGE.last} characters" }
        val principal = authentication.principal()?.asPrincipal() ?: throw SecurityException("Unauthorized access")
        val credential = securityService
            .getCredentials(principal)
            .firstOrNull { it.type.isPasswordCredential() } ?: throw SecurityException("Unauthorized access")
        securityService.loginWithCredential(SimplePasswordAttributes(credential.attributes.identifier, oldPassword), generateRefreshToken = false)
        securityService.updatePassword(principal.id, newPassword, identifier)
        true
    }

    @Field
    suspend fun setPrimaryProfile(
        authentication: AuthenticationContext,
        profileId: bosca.serialization.UUID,
        principalId: bosca.serialization.UUID?
    ): Boolean {
        if (principalId != null && !groupEvaluator.hasAdminGroup(authentication)) {
            throw SecurityException("Unauthorized access")
        }
        val targetPrincipalId = principalId ?: authentication.principal()?.id ?: throw SecurityException("Unauthorized access")
        if (!groupEvaluator.hasAdminGroup(authentication) && !groupEvaluator.hasSaGroup(authentication)) {
            val ownedProfiles = profileService.getByPrincipal(targetPrincipalId).map { it.id }.toSet()
            if (profileId !in ownedProfiles) {
                throw SecurityException("Unauthorized access")
            }
        }
        securityService.setPrimaryProfile(targetPrincipalId, profileId)
        return true
    }

    @Field
    suspend fun clearPrimaryProfile(
        authentication: AuthenticationContext,
        principalId: bosca.serialization.UUID?
    ): Boolean {
        if (principalId != null && !groupEvaluator.hasAdminGroup(authentication)) {
            throw SecurityException("Unauthorized access")
        }
        val targetPrincipalId = principalId ?: authentication.principal()?.id ?: throw SecurityException("Unauthorized access")
        securityService.clearPrimaryProfile(targetPrincipalId)
        return true
    }

    @Field
    suspend fun revokeLogin(authentication: AuthenticationContext, loginId: Long): Boolean {
        val principalId = authentication.requirePrincipalId()
        return securityService.revokePrincipalLogin(principalId, loginId) != null
    }
}
