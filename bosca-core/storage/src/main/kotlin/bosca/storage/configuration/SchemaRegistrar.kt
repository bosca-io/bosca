package bosca.storage.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("storagesystems.graphqls")
    val storagesystems: String
}