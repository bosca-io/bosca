package bosca.atlas.server.graphql

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {
    /** Composition roots and namespaces for the standalone Git API. */
    @Schema("atlas-server.graphqls") val root: String

    /** Profile and search fields required to choose Git repository owners. */
    @Schema("atlas-identity.graphqls") val identity: String

    /** Shared scalar contracts supplied by Bosca Core. */
    @Schema("types.graphqls") val scalars: String
}
