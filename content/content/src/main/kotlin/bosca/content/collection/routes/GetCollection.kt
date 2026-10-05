package bosca.content.collection.routes

import bosca.graphql.GraphQLService
import bosca.routes.GraphQLProxyRoute
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@RouteController("/api/v1/content/collection/{id}")
class GetCollection(
    graphQLService: GraphQLService,
    tracer: Tracer,
    json: Json,
) : GraphQLProxyRoute(graphQLService, tracer, json) {

    override suspend fun getOperationName(call: ServerCall, authenticationContext: AuthenticationContext) = "GetCollection"

    override suspend fun getVariables(call: ServerCall, authenticationContext: AuthenticationContext): JsonObject =
        JsonObject(mapOf("id" to JsonPrimitive(call.pathParameters["id"])))

    override suspend fun getQuery(call: ServerCall, authenticationContext: AuthenticationContext) = """
            query GetCollection(${'$'}id: String!) {
                content {
                    collections {
                        collection(id: ${'$'}id) {
                            id
                            name
                            description
                            attributes
                            languageTag
                            metadataRelationships {
                                relationship
                                attributes
                                metadata {
                                    id
                                    slug
                                    name
                                    attributes
                                }
                            }
                            languageVariant {
                                languageTag
                                name
                                description
                                attributes
                            }
                        }
                    }
                }
            }
        """.trimIndent()

    override val responsePath = listOf("content", "collections", "collection")
}