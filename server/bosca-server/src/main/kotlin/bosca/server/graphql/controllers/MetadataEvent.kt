package bosca.server.graphql.controllers

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class MetadataEvent(
    val type: String,
    @Contextual
    val id: UUID,
    val version: Int
)

@TypeController
class MetadataEventController : GraphQLController<MetadataEvent> {

    @Field
    fun type(event: MetadataEvent) = event.type

    @Field
    fun id(event: MetadataEvent) = event.id

    @Field
    fun version(event: MetadataEvent) = event.version
}