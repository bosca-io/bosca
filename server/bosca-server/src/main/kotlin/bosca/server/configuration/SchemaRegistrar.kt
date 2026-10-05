package bosca.server.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Declares the GraphQL schema resources that should be loaded and merged
 * into the server's GraphQL runtime.
 *
 * Annotated with [@Schemas] so the KSP processor discovers it and generates
 * the code that reads each [@Schema]-annotated property from the classpath
 * at startup. Additional schema files can be registered by adding new
 * properties annotated with [@Schema].
 */
@Schemas
interface SchemaRegistrar {

    /**
     * The root query schema resource, loaded from `query.graphqls` on the classpath.
     * Defines the top-level `Query` type and its fields.
     */
    @Schema("query.graphqls")
    val query: String
}