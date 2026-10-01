package bosca.git.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Registers the GraphQL schema files for the git server's API surface. The KSP
 * annotation processor merges these `.graphqls` resources into the server's
 * unified GraphQL schema at startup.
 */
@Schemas
interface SchemaRegistrar {

    @Schema("git.graphqls")
    val git: String
}
