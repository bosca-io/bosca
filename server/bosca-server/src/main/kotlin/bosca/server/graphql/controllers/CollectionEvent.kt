package bosca.server.graphql.controllers

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class CollectionEvent(
    val type: String,
    @Contextual
    val id: UUID,
    val languageTag: String? = null
)

@TypeController
class CollectionEventController : GraphQLController<CollectionEvent> {

    @Field
    fun type(event: CollectionEvent) = event.type

    @Field
    fun id(event: CollectionEvent) = event.id

    @Field
    fun languageTag(event: CollectionEvent) = event.languageTag

}
