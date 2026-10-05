package bosca.search.model

import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Metadata
import bosca.profile.model.Profile
import bosca.profile.organization.model.Organization
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
data class SearchDocumentItem(
    val id: String,
    val name: String
)

@Serializable
data class SearchDocument(
    val collection: Collection? = null,
    val metadata: Metadata? = null,
    val profile: Profile? = null,
    val organization: Organization? = null
)

@Serializable
enum class SearchDocumentType {
    @SerialName("collection")
    COLLECTION,

    @SerialName("metadata")
    METADATA,

    @SerialName("profile")
    PROFILE,

    @SerialName("organization")
    ORGANIZATION
}

@Serializable
data class SearchDocumentInput(
    @Contextual
    val id: UUID,
    val type: SearchDocumentType,
    val title: String,
    val content: String? = null,
    val attributes: JsonElement? = null
) {

    fun toJsonElement(): JsonElement {
        var document = JsonObject(mapOf(
            "id" to JsonPrimitive(id.toString()),
            "type" to JsonPrimitive(type.name.lowercase()),
            "title" to JsonPrimitive(title),
            "content" to JsonPrimitive(content),
        ))
        attributes?.takeIf { it !is JsonNull }?.let {
            val m = document.toMutableMap()
            m.putAll(it.jsonObject.toMap())
            document = JsonObject(m)
        }
        return document
    }
}
