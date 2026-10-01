package bosca.segmentation.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("segmentation.graphqls")
    val segmentation: String
}
