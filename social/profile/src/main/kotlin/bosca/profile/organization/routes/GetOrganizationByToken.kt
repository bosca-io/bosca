package bosca.profile.organization.routes

import bosca.graphql.GraphQLService
import bosca.routes.GraphQLProxyRoute
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@RouteController("/api/v1/organizations/token/{token}")
class GetOrganizationByToken(
    graphQLService: GraphQLService,
    tracer: Tracer,
    json: Json,
) : GraphQLProxyRoute(graphQLService, tracer, json) {

    override suspend fun getOperationName(call: ServerCall, authenticationContext: AuthenticationContext) = "GetOrganizationByToken"

    override suspend fun getVariables(call: ServerCall, authenticationContext: AuthenticationContext): JsonObject =
        JsonObject(mapOf("token" to JsonPrimitive(call.pathParameters["token"] ?: error("missing token"))))

    override suspend fun getQuery(call: ServerCall, authenticationContext: AuthenticationContext): String {
        return """
            query GetOrganizationByToken(${'$'}token: String!) {
              organizations {
                findByToken(token: ${'$'}token) {
                  id
                  name
                  visibility
                  attributes
                  profile {
                    id
                    name
                  }
                }
              }
            }
        """.trimIndent()
    }

    override val responsePath = listOf("organizations", "findByToken")
}
