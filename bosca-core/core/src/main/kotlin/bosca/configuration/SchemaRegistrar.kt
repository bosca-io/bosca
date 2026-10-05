package bosca.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Declares the core GraphQL schema files that are loaded and merged at startup.
 *
 * Each property maps to a `.graphqls` resource file via the [@Schema] annotation.
 * KSP processes this interface to generate the code that reads and registers
 * the schema definitions into the [SchemaRegistry][bosca.graphql.SchemaRegistry].
 */
@Schemas
interface SchemaRegistrar {

    /** Schema defining attribute-related types and fields. */
    @Schema("attributes.graphqls")
    val attributes: String

    /** Schema defining find/search query types. */
    @Schema("find.graphqls")
    val find: String

    /** Schema defining ordering/sorting types. */
    @Schema("ordering.graphqls")
    val ordering: String

    /** Schema defining persisted query support types. */
    @Schema("persistedqueries.graphqls")
    val persistedqueries: String

    /** Schema defining server-level query and mutation root types. */
    @Schema("server.graphqls")
    val server: String

    /** Schema defining core domain types (metadata, collections, etc.). */
    @Schema("types.graphqls")
    val types: String

    /** Schema defining package-related types. */
    @Schema("packages.graphqls")
    val packages: String
}