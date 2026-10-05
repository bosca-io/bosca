package bosca.comments.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {
    @Schema("comments.graphqls")
    val comments: String
}
