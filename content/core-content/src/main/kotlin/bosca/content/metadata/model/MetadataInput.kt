package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
data class MetadataInput(
    val name: String,
    val languageTag: String,
    val contentType: String,
    val contentLength: Long? = null,
    val metadataType: MetadataType? = null,
    @Contextual
    val parentId: UUID? = null,
    @Contextual
    val parentCollectionId: UUID? = null,
    val slug: String? = null,
    val locked: Boolean? = null,
    @Contextual
    val attributes: JsonElement = JsonNull,
    @Contextual
    val systemAttributes: JsonElement? = null,
    val document: DocumentInput? = null,
    val guide: GuideInput? = null,
    val data: DataInput? = null,
    val labels: List<String>? = null,
    val traitIds: List<String>? = null,
    @Contextual
    val categoryIds: List<UUID>? = null,
    val profiles: List<MetadataProfileInput>? = null,
    val source: MetadataSourceInput? = null,
    val collectionTemplate: CollectionTemplateInput? = null,
    val guideTemplate: GuideTemplateInput? = null,
    val documentTemplate: DocumentTemplateInput? = null,
    val dataTemplate: DataTemplateInput? = null,
    val syncVariantCollections: Boolean? = null,
    val syncVariantRelationships: Boolean? = null,
    val searchable: Boolean? = null,
    val recommendable: Boolean? = null,
) {

    fun toMetadata(stateId: String): Metadata {
        var attrs = attributes
        if (attrs !is JsonObject) {
            attrs = JsonObject(mapOf("published" to JsonPrimitive(System.currentTimeMillis())))
        } else {
            if (attrs.jsonObject["published"] == null) {
                attrs = JsonObject(attrs.jsonObject + JsonObject(mapOf("published" to JsonPrimitive(System.currentTimeMillis()))))
            }
        }
        return Metadata(
            name = name,
            type = metadataType ?: MetadataType.STANDARD,
            contentType = contentType.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            contentLength = contentLength,
            languageTag = languageTag,
            parentId = parentId,
            locked = locked ?: false,
            attributes = attrs,
            workflowStateId = stateId,
            labels = labels ?: emptyList(),
            sourceId = source?.id,
            sourceUrl = source?.sourceUrl,
            sourceIdentifier = source?.identifier,
            searchable = searchable ?: true,
            recommendable = recommendable ?: true,
            syncVariantCollections = syncVariantCollections ?: true,
            syncVariantRelationships = syncVariantRelationships ?: true,
        )
    }

    fun toMetadata(existing: Metadata): Metadata {
        val attributes = if (attributes is JsonNull) JsonObject(emptyMap()) else attributes
        if (attributes !is JsonObject) error("attributes must be an object")
        var attrs = attributes.jsonObject
        if (attrs["published"] == null) {
            val existingPublished = existing.attributes?.jsonObject?.get("published")
            if (existingPublished != null) {
                attrs = JsonObject(attrs + ("published" to existingPublished))
            } else {
                attrs = JsonObject(attrs + ("published" to JsonPrimitive(System.currentTimeMillis())))
            }
        }
        return existing.copy(
            name = name,
            type = metadataType ?: existing.type,
            contentType = contentType,
            contentLength = contentLength,
            languageTag = languageTag,
            parentId = parentId,
            locked = locked ?: existing.locked,
            attributes = attrs,
            labels = labels ?: existing.labels,
            sourceId = source?.id,
            sourceUrl = source?.sourceUrl,
            sourceIdentifier = source?.identifier,
            searchable = searchable ?: true,
            recommendable = recommendable ?: existing.recommendable,
            syncVariantCollections = syncVariantCollections ?: true,
            syncVariantRelationships = syncVariantRelationships ?: true,
        )
    }
}
