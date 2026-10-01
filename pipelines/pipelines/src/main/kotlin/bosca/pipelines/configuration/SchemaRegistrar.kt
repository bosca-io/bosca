package bosca.pipelines.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Declares the GraphQL schema files contributed by the pipelines module. KSP processes this to
 * generate `PipelinesSchemaRegistrar`, which the server merges into the runtime schema.
 */
@Schemas
interface SchemaRegistrar {

    /** `Query.pipelines` / `Mutation.pipelines` — pipeline CRUD. */
    @Schema("pipelines.graphqls")
    val pipelines: String
}
