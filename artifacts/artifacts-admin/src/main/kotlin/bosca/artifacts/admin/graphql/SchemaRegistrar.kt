package bosca.artifacts.admin.graphql

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("artifacts-admin.graphqls")
    val artifactsAdmin: String
}
