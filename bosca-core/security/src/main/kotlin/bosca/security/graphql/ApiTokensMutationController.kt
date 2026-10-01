package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.ApiToken
import bosca.security.model.ApiTokenCreationResponse
import bosca.security.model.toApiToken
import bosca.security.service.ApiTokenInput
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.serialization.UUID

object ApiTokensMutation

/**
 * GraphQL controller for API token mutation operations.
 * Handles token creation, revocation, editing, and deletion.
 */
@TypeController
class ApiTokensMutationController(
    private val apiTokenService: ApiTokenService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ApiTokensMutation> {

    @Field
    suspend fun create(
        authentication: AuthenticationContext,
        input: ApiTokenInput,
    ): ApiTokenCreationResponse {
        val principal = authentication.requireInteractivePrincipal()
        val result = apiTokenService.createToken(principal.id, input, createdBy = principal.id)
        return ApiTokenCreationResponse(
            apiToken = result.credential.toApiToken(),
            rawToken = result.rawToken,
        )
    }

    @Field
    suspend fun createForPrincipal(
        authentication: AuthenticationContext,
        principalId: UUID,
        input: ApiTokenInput,
    ): ApiTokenCreationResponse {
        val principal = authentication.requireInteractivePrincipal()
        // Creating a token for oneself is identical to [create]; only minting a token
        // for *another* principal requires admin.
        if (principal.id != principalId) {
            groupEvaluator.verifyHasAdminGroup(authentication)
        }
        val result = apiTokenService.createToken(principalId, input, createdBy = principal.id)
        return ApiTokenCreationResponse(
            apiToken = result.credential.toApiToken(),
            rawToken = result.rawToken,
        )
    }

    @Field
    suspend fun revoke(
        authentication: AuthenticationContext,
        id: Long,
    ): Boolean {
        val principalId = requireTokenOwnerOrInteractive(authentication, id)
        apiTokenService.revokeToken(id, principalId)
        return true
    }

    @Field
    suspend fun revokeAll(
        authentication: AuthenticationContext,
    ): Boolean {
        val principalId = authentication.requireInteractivePrincipal().id
        apiTokenService.revokeAllTokens(principalId)
        return true
    }

    @Field
    suspend fun revokeAllForPrincipal(
        authentication: AuthenticationContext,
        principalId: UUID,
    ): Boolean {
        // Revoking all of one's own tokens is identical to [revokeAll]; only revoking
        // *another* principal's tokens requires admin.
        val callerId = authentication.requireInteractivePrincipal().id
        if (callerId != principalId) {
            groupEvaluator.verifyHasAdminGroup(authentication)
        }
        apiTokenService.revokeAllTokens(principalId)
        return true
    }

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        id: Long,
        name: String?,
        description: String?,
        scopes: List<String>?,
    ): ApiToken {
        val principalId = authentication.requireInteractivePrincipal().id
        val updated = apiTokenService.editToken(id, name, description, scopes, principalId)
        return updated.toApiToken()
    }

    @Field
    suspend fun delete(
        authentication: AuthenticationContext,
        id: Long,
    ): Boolean {
        val principalId = requireTokenOwnerOrInteractive(authentication, id)
        apiTokenService.deleteToken(id, principalId)
        return true
    }

    /**
     * Lets an API token revoke or delete a single token owned by its own principal, so a
     * leaked token can be contained without an interactive login. Interactive callers keep
     * the service's owner-or-administrator rule.
     */
    private suspend fun requireTokenOwnerOrInteractive(authentication: AuthenticationContext, id: Long): UUID {
        val principal = authentication.requirePrincipal()
        if (principal is ScopedAuthenticatedPrincipal) {
            val credential = apiTokenService.getTokenById(id) ?: throw SecurityException("API token not found")
            if (credential.principal != principal.id) {
                throw SecurityException("API tokens can only manage tokens owned by the same account")
            }
        }
        return principal.id
    }
}
