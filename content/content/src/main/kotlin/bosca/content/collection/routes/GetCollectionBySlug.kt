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

@RouteController("/api/v1/content/slug/collection/{slug}")
class GetCollectionBySlug(
    graphQLService: GraphQLService,
    tracer: Tracer,
    json: Json,
) : GraphQLProxyRoute(graphQLService, tracer, json) {

    override suspend fun getOperationName(call: ServerCall, authenticationContext: AuthenticationContext) = "GetCollectionBySlug"

    override suspend fun getVariables(call: ServerCall, authenticationContext: AuthenticationContext): JsonObject =
        JsonObject(mapOf("slug" to JsonPrimitive(call.pathParameters["slug"] ?: error("missing slug"))))

    override suspend fun getQuery(call: ServerCall, authenticationContext: AuthenticationContext): String {
        return """
            query GetCollectionBySlug(${'$'}slug: String!) {
              content {
                slug(slug: ${'$'}slug) {
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
                        slug
                        attributes
                      }
                    }
                  } 
                }
              }
            }
        """.trimIndent()
    }

    override val responsePath = listOf("content", "slug")
}