package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.workops.ui.generated.WorkOpsImages
import bosca.workops.ui.graphql.WorkOpsCompleteImage
import bosca.workops.ui.graphql.WorkOpsImage
import bosca.workops.ui.graphql.WorkOpsImageChoices
import bosca.workops.ui.graphql.WorkOpsPrepareImage
import bosca.workops.ui.graphql.WorkOpsRetryImage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

/** Adapts the existing Content/search upload APIs to the shared Design Library image picker. */
class WorkOpsImageLookup : WorkOpsImages {
    private val rowsSerializer = ListSerializer(MapSerializer(String.serializer(), String.serializer()))

    override suspend fun search(gql: GraphQLClient, query: String, offset: Int): Map<String, String> {
        require(offset >= 0) { "Invalid image page." }
        val documents = gql.execute(WorkOpsImageChoices, WorkOpsImageChoices.Variables(query, offset)).search.search?.documents.orEmpty()
        val items = documents.take(25).mapNotNull { document ->
            document.metadata?.let { metadata ->
                metadata.content?.takeIf { metadata.uploaded != null && it.type.startsWith("image/") && it.urls?.download != null }?.let {
                    choice(metadata.id, metadata.name, it.type)
                }
            }
        }
        return buildMap {
            put("items", Json.encodeToString(rowsSerializer, items))
            if (offset > 0) put("previousOffset", maxOf(0, offset - 25).toString())
            if (documents.size > 25) put("nextOffset", (offset + 25).toString())
        }
    }

    override suspend fun resolve(gql: GraphQLClient, id: String): Map<String, String>? {
        UUID.fromString(id)
        val metadata = gql.execute(WorkOpsImage, WorkOpsImage.Variables(id)).content.metadata ?: return null
        val content = metadata.content ?: return null
        if (metadata.uploaded == null || !content.type.startsWith("image/") || content.urls?.download == null) return null
        return choice(metadata.id, metadata.name, content.type)
    }

    override suspend fun prepare(gql: GraphQLClient, name: String, type: String, length: Long, existingId: String): Map<String, String> {
        require(name.isNotBlank() && type.startsWith("image/") && length >= 0) { "Choose an image file with an identified image content type." }
        val id: String
        val url: String
        val headers: List<Map<String, String>>
        if (existingId.isBlank()) {
            val metadata = gql.execute(WorkOpsPrepareImage, WorkOpsPrepareImage.Variables(name, type, length)).content.metadata.add
            id = metadata.id
            val target = requireNotNull(metadata.content?.urls?.upload) { "Image upload is unavailable." }
            url = target.url
            headers = target.headers.map { mapOf("name" to it.name, "value" to it.value) }
        } else {
            UUID.fromString(existingId)
            val metadata = gql.execute(WorkOpsRetryImage, WorkOpsRetryImage.Variables(existingId)).content.metadata
                ?: throw NoSuchElementException("Image upload is unavailable.")
            id = metadata.id
            val target = requireNotNull(metadata.content?.urls?.upload) { "Image upload is unavailable or you do not have access to it." }
            url = target.url
            headers = target.headers.map { mapOf("name" to it.name, "value" to it.value) }
        }
        // Content's signed upload is relayed through this imported project's same-origin route.
        val target = URI(url)
        require(target.rawPath.endsWith("/api/v1/content/metadata/upload")) { "Unsupported Content upload target." }
        return mapOf("id" to id, "url" to "/api/v1/content/metadata/upload?${target.rawQuery}",
            "headers" to Json.encodeToString(rowsSerializer, headers))
    }

    override suspend fun complete(gql: GraphQLClient, id: String): Map<String, String> {
        val image = resolve(gql, id) ?: throw NoSuchElementException("Image upload has not completed or you do not have access to it.")
        check(gql.execute(WorkOpsCompleteImage, WorkOpsCompleteImage.Variables(id)).content.metadata.setMetadataReady) { "Could not complete the image upload." }
        return image
    }

    private fun choice(id: String, name: String, type: String) = mapOf("id" to id,
        "label" to workOpsContentLabel(name, id, "Untitled image"), "src" to "/content/image/$id", "contentType" to type)
}
