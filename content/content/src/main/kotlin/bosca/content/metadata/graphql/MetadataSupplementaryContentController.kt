package bosca.content.metadata.graphql

import bosca.content.metadata.model.MetadataSupplementaryContent
import bosca.content.metadata.model.MetadataSupplementaryContentUrls
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.storage.service.ObjectStorageService

@TypeController
class MetadataSupplementaryContentController(
    private val storage: ObjectStorageService
) : GraphQLController<MetadataSupplementaryContent> {

    @Field
    fun length(content: MetadataSupplementaryContent) = content.supplementary.contentLength

    @Field
    fun type(content: MetadataSupplementaryContent) = content.supplementary.contentType

    @Field
    fun urls(content: MetadataSupplementaryContent) =
        MetadataSupplementaryContentUrls(content.metadata, content.supplementary)

    @Field
    suspend fun json(content: MetadataSupplementaryContent): Any? {
        val path = storage.getPath(content.metadata, content.supplementary.id)
        val json = storage.getString(path)
        return json
    }

    @Field
    suspend fun text(content: MetadataSupplementaryContent): String? {
        val path = storage.getPath(content.metadata, content.supplementary.id)
        return storage.getString(path)
    }
}