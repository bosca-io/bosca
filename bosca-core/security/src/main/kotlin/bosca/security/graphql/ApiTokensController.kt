package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.ApiToken
import bosca.security.model.toApiToken
import bosca.security.service.ApiTokenScope
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object ApiTokens

/**
 * GraphQL controller for API token query operations.
 * Provides token listing, retrieval by ID, and scope discovery.
 */
@TypeController
class ApiTokensController(
    private val apiTokenService: ApiTokenService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ApiTokens> {

    @Field
    suspend fun my(
        authentication: AuthenticationContext,
        limit: Int,
        offset: Long,
    ): List<ApiToken> {
        val principalId = authentication.requirePrincipalId()
        val tokens = apiTokenService.getTokensForPrincipal(principalId)
        return tokens.drop(offset.toInt()).take(limit).map { it.toApiToken() }
    }

    @Field
    suspend fun forPrincipal(
        authentication: AuthenticationContext,
        principalId: UUID,
        limit: Int,
        offset: Long,
    ): List<ApiToken> {
        // A principal may always list their own tokens; only listing *another*
        // principal's tokens requires admin. Mirrors the self-or-admin check in [token].
        val callerId = authentication.requirePrincipalId()
        if (callerId != principalId) {
            groupEvaluator.verifyHasAdminGroup(authentication)
        }
        val tokens = apiTokenService.getTokensForPrincipal(principalId)
        return tokens.drop(offset.toInt()).take(limit).map { it.toApiToken() }
    }

    @Field
    suspend fun token(
        authentication: AuthenticationContext,
        id: Long,
    ): ApiToken? {
        val principalId = authentication.requirePrincipalId()
        val credential = apiTokenService.getTokenById(id) ?: return null
        if (credential.principal != principalId) {
            groupEvaluator.verifyHasAdminGroup(authentication)
        }
        return credential.toApiToken()
    }

    @Field
    fun availableScopes(authentication: AuthenticationContext): List<ApiTokenScope> {
        authentication.requirePrincipal()
        return apiTokenService.availableScopes()
    }
}
