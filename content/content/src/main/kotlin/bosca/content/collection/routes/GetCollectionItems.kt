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

@RouteController("/api/v1/content/collection/{id}/items")
class GetCollectionItems(
    graphQLService: GraphQLService,
    tracer: Tracer,
    json: Json,
) : GraphQLProxyRoute(graphQLService, tracer, json) {

    override suspend fun getOperationName(call: ServerCall, authenticationContext: AuthenticationContext) = "GetCollectionItems"

    override suspend fun getVariables(call: ServerCall, authenticationContext: AuthenticationContext): JsonObject {
        val vars = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "id" to JsonPrimitive(call.pathParameters["id"]),
            "offset" to JsonPrimitive(call.request.queryParameters["offset"]?.toIntOrNull() ?: 0),
            "limit" to JsonPrimitive(call.request.queryParameters["limit"]?.toIntOrNull() ?: 10),
        )
        call.request.queryParameters["language"]?.let { vars["languageTag"] = JsonPrimitive(it) }
        return JsonObject(vars)
    }

    override suspend fun getQuery(call: ServerCall, authenticationContext: AuthenticationContext): String {
        val hasLanguageTag = call.request.queryParameters["language"] != null
        return """
                query GetCollectionItems(${'$'}id: String!, ${'$'}offset: Int!, ${'$'}limit: Int!${if (hasLanguageTag) ", ${'$'}languageTag: String" else ""}) {
                    content {
                        collections {
                            collection(id: ${'$'}id) {
                                items(offset: ${'$'}offset, limit: ${'$'}limit${if (hasLanguageTag) ", languageTag: ${'$'}languageTag" else ""}) {
                                    ... on Metadata {
                                        __typename
                                        id
                                        name
                                        slug
                                        languageTag
                                        relationships {
                                            relationship
                                            attributes
                                            metadata {
                                                id
                                                attributes
                                                slug
                                            }
                                        }
                                    }
                                    ... on Collection {
                                        __typename
                                        id
                                        name
                                        slug
                                        languageTag
                                        languageVariant {
                                            name
                                        }
                                        metadataRelationships {
                                            relationship
                                            attributes
                                            metadata {
                                                id
                                                attributes
                                                slug
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()
    }

    override val responsePath = listOf("content", "collections", "collection")
}