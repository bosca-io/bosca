package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.CredentialType
import bosca.security.model.PasskeyCredentialAttributes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.security.routes.passkey.PasskeyInfo
import bosca.serialization.UUID

object Passkeys

/**
 * GraphQL controller for passkey query operations.
 * Lists the current principal's registered WebAuthn passkeys.
 */
@TypeController
class PasskeysController(
    private val securityService: SecurityService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Passkeys> {

    @Field
    suspend fun current(authentication: AuthenticationContext): List<PasskeyInfo> {
        val principal = authentication.requirePrincipal().asPrincipal()
        return credentialsToPasskeyInfo(principal)
    }

    @Field
    suspend fun all(authentication: AuthenticationContext, principalId: UUID): List<PasskeyInfo> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val principal = securityService.getPrincipalById(principalId)
            ?: throw SecurityException("Principal not found")
        return credentialsToPasskeyInfo(principal)
    }

    private suspend fun credentialsToPasskeyInfo(principal: bosca.security.model.Principal): List<PasskeyInfo> {
        val credentials = securityService.getCredentials(principal, CredentialType.PASSKEY)
        return credentials.map { cred ->
            val attrs = cred.attributes as? PasskeyCredentialAttributes
                ?: error("Credential ${cred.id} has type PASSKEY but attributes are ${cred.attributes::class.simpleName}")
            PasskeyInfo(
                credentialId = attrs.identifier,
                name = attrs.name,
                createdAt = attrs.createdAt,
                lastUsedAt = attrs.lastUsedAt,
                transports = attrs.transports,
            )
        }
    }
}
