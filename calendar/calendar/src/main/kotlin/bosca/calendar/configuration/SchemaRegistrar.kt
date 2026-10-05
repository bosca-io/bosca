package bosca.calendar.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("calendar.graphqls")
    val calendar: String
}
