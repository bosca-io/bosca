package bosca.content.metadata.graphql

import bosca.content.metadata.model.MetadataContent
import bosca.content.metadata.model.MetadataContentUrls
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.storage.service.ObjectStorageService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

import org.slf4j.LoggerFactory

@TypeController
class MetadataContentController(
    private val storage: ObjectStorageService,
    private val json: Json
) : GraphQLController<MetadataContent> {

    @Field
    suspend fun json(content: MetadataContent): JsonElement? {
        if (!content.allowed) return null
        try {
            val path = storage.getPath(content.metadata)
            val jsonString = storage.getString(path)
            return json.parseToJsonElement(jsonString)
        } catch (e: Exception) {
            log.error("Failed to retrieve JSON from storage", e)
            return null
        }
    }

    @Field
    suspend fun text(content: MetadataContent): String? {
        if (!content.allowed) return null
        try {
            val path = storage.getPath(content.metadata)
            return storage.getString(path)
        } catch (e: Exception) {
            log.error("Failed to retrieve text from storage", e)
            return null
        }
    }

    @Field
    fun type(content: MetadataContent) = content.metadata.contentType

    @Field
    fun length(content: MetadataContent) = content.metadata.contentLength

    @Field
    fun urls(content: MetadataContent) = MetadataContentUrls(content.metadata)

    companion object {

        private val log = LoggerFactory.getLogger(MetadataContentController::class.java)
    }
}