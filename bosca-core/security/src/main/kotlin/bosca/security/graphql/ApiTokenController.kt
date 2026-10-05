package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.ApiToken
import bosca.security.model.ApiTokenCreationResponse
import bosca.security.service.ApiTokenScope
import bosca.serialization.UUID
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Resolves the `ApiToken` GraphQL type fields from the [ApiToken] model.
 */
@TypeController("ApiToken")
class ApiTokenController : GraphQLController<ApiToken> {

    @Field
    fun id(token: ApiToken) = token.credential.id

    @Field
    fun principalId(token: ApiToken) = token.credential.principal

    @Field
    fun name(token: ApiToken) = token.attrs.name

    @Field
    fun description(token: ApiToken) = token.attrs.description

    @Field
    fun tokenPrefix(token: ApiToken) = token.attrs.tokenPrefix

    @Field
    fun scopes(token: ApiToken) = token.attrs.scopes

    @Field
    fun allowedGroups(token: ApiToken): List<UUID>? =
        token.attrs.allowedGroups?.map { UUID.parse(it) }

    @Field
    fun expiresAt(token: ApiToken): OffsetDateTime? =
        optionalTimestamp(token.attrs.expiresAt)

    @Field
    fun lastUsedAt(token: ApiToken): OffsetDateTime? =
        optionalTimestamp(token.attrs.lastUsedAt)

    @Field
    fun lastUsedIp(token: ApiToken) = token.attrs.lastUsedIp

    @Field
    fun revokedAt(token: ApiToken): OffsetDateTime? =
        optionalTimestamp(token.attrs.revokedAt)

    @Field
    fun active(token: ApiToken): Boolean {
        if (token.attrs.revokedAt != null) return false
        val expiresAt = token.attrs.expiresAt
        if (expiresAt != null) {
            val expiry = parseTimestamp(expiresAt)
            if (expiry == null || expiry.isBefore(OffsetDateTime.now())) return false
        }
        return true
    }

    private fun optionalTimestamp(value: String?): OffsetDateTime? =
        value?.let(::parseTimestamp)

    private fun parseTimestamp(value: String): OffsetDateTime? {
        return try {
            OffsetDateTime.parse(value)
        } catch (e: DateTimeParseException) {
            log.debug("Malformed timestamp in API token credential attributes: '{}'", value, e)
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ApiTokenController::class.java)
    }
}

/**
 * Resolves the `ApiTokenCreationResponse` GraphQL type from [ApiTokenCreationResponse].
 */
@TypeController("ApiTokenCreationResponse")
class ApiTokenCreationResponseController : GraphQLController<ApiTokenCreationResponse> {

    @Field
    fun apiToken(response: ApiTokenCreationResponse) = response.apiToken

    @Field
    fun rawToken(response: ApiTokenCreationResponse) = response.rawToken
}

/**
 * Resolves the `ApiTokenScopeInfo` GraphQL type from [ApiTokenScope].
 */
@TypeController("ApiTokenScopeInfo")
class ApiTokenScopeInfoController : GraphQLController<ApiTokenScope> {

    @Field
    fun name(scope: ApiTokenScope) = scope.name

    @Field
    fun description(scope: ApiTokenScope) = scope.description
}
