package bosca.search.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("search.graphqls")
    val search: String
}