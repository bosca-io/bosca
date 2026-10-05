package bosca.recommendations.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Registers the GraphQL schema files that define the recommendation system's API surface.
 * The KSP annotation processor reads these declarations to merge the listed `.graphqls`
 * resources into the server's unified GraphQL schema at startup.
 */
@Schemas
interface SchemaRegistrar {

    /**
     * Path to the GraphQL schema definition that declares recommendation queries,
     * mutations, and types such as placements, strategies, and dismissals.
     */
    @Schema("recommendations.graphqls")
    val recommendations: String
}
