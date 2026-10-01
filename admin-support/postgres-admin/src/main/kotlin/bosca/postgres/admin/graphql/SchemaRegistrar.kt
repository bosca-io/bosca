package bosca.postgres.admin.graphql

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("postgres-admin.graphqls")
    val postgresAdmin: String
}
