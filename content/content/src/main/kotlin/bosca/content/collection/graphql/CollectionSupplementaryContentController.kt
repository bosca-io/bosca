package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionSupplementaryContent
import bosca.content.collection.model.CollectionSupplementaryContentUrls
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.JsonConverter.parseToJsonElement
import bosca.storage.service.ObjectStorageService
import kotlinx.serialization.json.JsonElement


@TypeController
class CollectionSupplementaryContentController(
    private val storage: ObjectStorageService
) : GraphQLController<CollectionSupplementaryContent> {

    @Field
    fun length(content: CollectionSupplementaryContent) = content.supplementary.contentLength

    @Field
    fun type(content: CollectionSupplementaryContent) = content.supplementary.contentType

    @Field
    fun urls(content: CollectionSupplementaryContent) =
        CollectionSupplementaryContentUrls(content.collection, content.supplementary)

    @Field
    suspend fun json(content: CollectionSupplementaryContent): JsonElement {
        val path = storage.getPath(content.collection, content.supplementary.id)
        val json = storage.getString(path)
        return json.parseToJsonElement()
    }

    @Field
    suspend fun text(content: CollectionSupplementaryContent): String {
        val path = storage.getPath(content.collection, content.supplementary.id)
        return storage.getString(path)
    }
}