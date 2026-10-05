package bosca.experimentation.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("experimentation.graphqls")
    val experimentation: String
}
