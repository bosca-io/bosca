package bosca.configuration.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("configurations.graphqls")
    val configurations: String
}