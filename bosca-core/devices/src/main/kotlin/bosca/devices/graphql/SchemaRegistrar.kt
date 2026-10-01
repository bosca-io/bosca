package bosca.devices.graphql

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("devices.graphqls")
    val devices: String
}
