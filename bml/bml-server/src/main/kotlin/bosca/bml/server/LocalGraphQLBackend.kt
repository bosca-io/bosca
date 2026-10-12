package bosca.bml.server

import bosca.bml.graphql.GraphQLBackend
import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.GraphQLException
import bosca.bml.graphql.GraphQLResponse
import bosca.graphql.GraphQLRequest
import bosca.graphql.GraphQLService
import bosca.server.ServerCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Runs BML operations through the host's GraphQL engine without an HTTP round trip. The host
 * installs its normal GraphQL/security routes for browser requests and supplies credential
 * validation here for SSR and island actions. Resolvers receive the original call, so request
 * metadata and session changes have the same lifetime as the page or action.
 */
class LocalGraphQLBackend(
    private val service: GraphQLService,
    private val requestMetadata: (ServerCall, String?, String?) -> Unit = { _, _, _ -> },
    private val authenticate: suspend (ServerCall, String?) -> Boolean,
) : GraphQLBackend {
    private val json = Json { ignoreUnknownKeys = true }

    override fun prepareRequest(
        call: ServerCall,
        installationId: String?,
        analyticsSessionId: String?,
    ) {
        requestMetadata(call, installationId, analyticsSessionId)
    }

    override fun forRequest(
        call: ServerCall,
        token: String?,
        installationId: String?,
        analyticsSessionId: String?,
    ): GraphQLClient {
        val defaultToken = token
        return object : GraphQLClient {
            override suspend fun execute(
                query: String,
                variables: JsonObject?,
                operationName: String?,
                token: String?,
            ): JsonElement {
                if (!authenticate(call, token ?: defaultToken)) {
                    throw GraphQLException("GraphQL HTTP 401: Unauthorized")
                }
                prepareRequest(call, installationId, analyticsSessionId)
                val result =
                    service.execute(
                        call,
                        GraphQLRequest(
                            query = query,
                            variables = variables,
                            operationName = operationName,
                        ),
                    )
                val response = json.decodeFromJsonElement(GraphQLResponse.serializer(), result)
                val errors = response.errors
                if (!errors.isNullOrEmpty()) {
                    throw GraphQLException(errors.joinToString("; ") { it.message })
                }
                return response.data ?: JsonNull
            }
        }
    }
}
