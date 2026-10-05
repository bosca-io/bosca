package bosca.nats.admin.graphql

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("nats-admin.graphqls")
    val natsAdmin: String
}
