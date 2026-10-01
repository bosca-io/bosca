package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.CredentialType
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService

object PasskeysMutation

/**
 * GraphQL controller for passkey mutation operations.
 * Handles deletion of registered WebAuthn passkeys.
 */
@TypeController
class PasskeysMutationController(
    private val securityService: SecurityService,
) : GraphQLController<PasskeysMutation> {

    /** Deletes the caller's passkey using account authentication rather than an API token. */
    @Field
    suspend fun delete(authentication: AuthenticationContext, credentialId: String): Boolean {
        val principalId = authentication.requireInteractivePrincipal().id
        securityService.deleteCredential(principalId, CredentialType.PASSKEY, credentialId)
        return true
    }
}
