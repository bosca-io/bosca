package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.ApiTokenInput
import bosca.graphql.gen.CreateApiToken
import bosca.graphql.gen.CreateApiTokenData
import bosca.graphql.gen.DeleteApiToken
import bosca.graphql.gen.EditApiToken
import bosca.graphql.gen.GetApiToken
import bosca.graphql.gen.GetAvailableApiTokenScopes
import bosca.graphql.gen.GetAvailableApiTokenScopesData
import bosca.graphql.gen.GetMyApiTokens
import bosca.graphql.gen.IApiTokenFragment
import bosca.graphql.gen.RevokeApiToken
import java.time.ZonedDateTime

class ApiTokens(network: NetworkClient) : Api(network) {

    suspend fun list(limit: Int = 25, offset: Long = 0): List<IApiTokenFragment> =
        network.boscaGraphql.execute(GetMyApiTokens, GetMyApiTokens.Variables(limit, offset)).security.apiTokens.my

    suspend fun get(id: Long): IApiTokenFragment? =
        network.boscaGraphql.execute(GetApiToken, GetApiToken.Variables(id)).security.apiTokens.token

    suspend fun availableScopes(): List<GetAvailableApiTokenScopesData.Security.ApiTokens.AvailableScopes> =
        network.boscaGraphql.execute(GetAvailableApiTokenScopes, Unit).security.apiTokens.availableScopes

    suspend fun create(
        name: String,
        description: String? = null,
        scopes: List<String>? = null,
        expiresAt: ZonedDateTime? = null,
    ): CreateApiTokenData.Security.ApiTokens.Create {
        val input = ApiTokenInput(
            name = name,
            description = description,
            scopes = scopes,
            allowedGroups = null,
            expiresAt = expiresAt,
        )
        return network.boscaGraphql.execute(CreateApiToken, CreateApiToken.Variables(input)).security.apiTokens.create
    }

    suspend fun revoke(id: Long): Boolean =
        network.boscaGraphql.execute(RevokeApiToken, RevokeApiToken.Variables(id)).security.apiTokens.revoke

    suspend fun delete(id: Long): Boolean =
        network.boscaGraphql.execute(DeleteApiToken, DeleteApiToken.Variables(id)).security.apiTokens.delete

    suspend fun edit(
        id: Long,
        name: String? = null,
        description: String? = null,
        scopes: List<String>? = null,
    ): IApiTokenFragment =
        network.boscaGraphql.execute(
            EditApiToken,
            EditApiToken.Variables(id = id, name = name, description = description, scopes = scopes),
        ).security.apiTokens.edit
}
