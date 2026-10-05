package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.CredentialType
import bosca.security.routes.security.PASSWORD_LENGTH_RANGE
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.security.service.ThirdPartyType
import bosca.serialization.UUID
import bosca.server.ServerCall
import org.slf4j.LoggerFactory

object SecurityMutation

@TypeController
class SecurityMutationController(
    private val securityService: SecurityService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<SecurityMutation> {

    @Field
    fun apiTokens() = ApiTokensMutation

    @Field
    fun passkeys() = PasskeysMutation

    @Field
    fun login() = LoginMutation

    @Field
    fun signup() = SignupMutation

    @Field
    fun link() = LinkMutation

    @Field
    fun admin() = AdminMutation

    @Field
    fun principal() = PrincipalMutation

    @Field
    fun groups() = GroupsMutation

    @Field
    suspend fun addPrincipalGroup(
        authentication: AuthenticationContext,
        principalId: UUID,
        groupId: UUID,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        securityService.addPrincipalGroup(principalId, groupId)
        return true
    }

    @Field
    suspend fun removePrincipalGroup(
        authentication: AuthenticationContext,
        principalId: UUID,
        groupId: UUID
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        securityService.removePrincipalGroup(principalId, groupId)
        return true
    }

    @Field
    suspend fun password(newPassword: String, verificationToken: String): Boolean {
        require(newPassword.length in PASSWORD_LENGTH_RANGE) { "Password must be between ${PASSWORD_LENGTH_RANGE.first} and ${PASSWORD_LENGTH_RANGE.last} characters" }
        return try {
            securityService.resetPassword(verificationToken, newPassword)
            true
        } catch (e: IllegalArgumentException) {
            log.warn("Password reset failed: invalid or expired token", e)
            false
        } catch (e: NoSuchElementException) {
            log.warn("Password reset failed: token not found", e)
            false
        }
    }

    @Field
    suspend fun setPassword(
        authentication: AuthenticationContext,
        principalId: UUID,
        password: String,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "Password must be at least $MIN_PASSWORD_LENGTH characters"
        }
        securityService.updatePassword(principalId, password)
        return true
    }

    @Field
    suspend fun sendPasswordReset(
        authentication: AuthenticationContext,
        principalId: UUID,
        call: ServerCall,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val principal = securityService.getPrincipalById(principalId)
            ?: throw NoSuchElementException("principal not found")
        val credentials = securityService.getCredentials(principal)
        val credential = credentials.firstOrNull {
            it.type.isPasswordCredential()
        }
            ?: throw NoSuchElementException("principal has no password credential")
        securityService.forgotPassword(credential.attributes.identifier, call.request.appOrigin)
        return true
    }

    @Field
    suspend fun deleteCredential(
        authentication: AuthenticationContext,
        principalId: UUID,
        type: CredentialType,
        identifier: String,
    ): Boolean {
        val callerId = authentication.requireInteractivePrincipal().id
        if (!groupEvaluator.hasAdminGroup(authentication)) {
            if (callerId != principalId || type != CredentialType.OAUTH2) {
                throw bosca.security.service.SecurityException("Unauthorized access")
            }
            val principal = securityService.getPrincipalById(principalId)
                ?: throw bosca.security.service.SecurityException("Unauthorized access")
            val hasPassword = securityService.getCredentials(principal).any { it.type.isPasswordCredential() }
            if (!hasPassword) {
                throw bosca.security.service.SecurityException("A password credential is required before disconnecting a third-party account")
            }
        }
        securityService.deleteCredential(principalId, type, identifier)
        return true
    }

    @Field
    suspend fun connectThirdParty(
        authentication: AuthenticationContext,
        type: ThirdPartyType,
        token: String,
    ): Boolean {
        val principalId = authentication.requireInteractivePrincipal().id
        securityService.connectThirdParty(principalId, type, token)
        return true
    }

    @Field
    suspend fun resendVerification(
        authentication: AuthenticationContext,
        principalId: UUID,
        call: ServerCall,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        securityService.sendVerificationEmail(principalId, call.request.appOrigin)
        return true
    }

    @Field
    suspend fun expireRefreshTokens(authentication: AuthenticationContext): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        securityService.deleteExpiredRefreshToken()
        return true
    }

    companion object {

        private const val MIN_PASSWORD_LENGTH = 8
        private val log = LoggerFactory.getLogger(SecurityMutationController::class.java)
    }
}
