package bosca.scripting.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("scripts.graphqls")
    val scripts: String
}
