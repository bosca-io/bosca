package bosca.content.collection.model

import bosca.content.ordering.OrderingInput
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
data class CollectionLanguageVariantInput(
    @Contextual
    val id: UUID,
    val languageTag: String,
    val name: String,
    val description: String? = null,
    @Contextual
    val attributes: JsonElement? = null,
    val public: Boolean = false,
    val publicList: Boolean = false,
    val publicSupplementary: Boolean = false,
    val searchable: Boolean? = null,
    val recommendable: Boolean? = null,
) {

    fun toVariant(workflowStateId: String = "pending") = CollectionLanguageVariant(
        id = id,
        languageTag = languageTag,
        name = name,
        description = description,
        attributes = attributes,
        workflowStateId = workflowStateId,
        public = public,
        publicList = publicList,
        publicSupplementary = publicSupplementary,
        searchable = searchable ?: true,
        recommendable = recommendable ?: true,
    )

    fun toVariant(current: CollectionLanguageVariant) = CollectionLanguageVariant(
        id = current.id,
        languageTag = current.languageTag,
        name = name,
        description = description,
        attributes = attributes,
        workflowStateId = current.workflowStateId,
        public = current.public,
        publicList = current.publicList,
        publicSupplementary = current.publicSupplementary,
        searchable = searchable ?: current.searchable,
        recommendable = recommendable ?: current.recommendable,
    )
}

@Serializable
data class CollectionInput(
    val name: String,
    val description: String? = null,
    val languageTag: String? = null,
    val collectionType: CollectionType? = null,
    @Contextual
    val attributes: JsonElement = JsonNull,
    @Contextual
    val systemAttributes: JsonElement? = null,
    @Contextual
    val categoryIds: List<UUID>? = null,
    val slug: String? = null,
    val locked: Boolean? = null,
    val labels: List<String>? = null,
    val ready: OffsetDateTime? = null,
    val deleteWorkflowId: String? = null,
    val traitIds: List<String>? = null,
    val itemsLocked: Boolean? = null,
    @Contextual
    val parentCollectionId: UUID? = null,
    @Contextual
    val templateMetadataId: UUID? = null,
    val templateMetadataVersion: Int? = null,
    val ordering: List<OrderingInput>? = null,
    val collections: List<CollectionChildInput>? = null,
    val metadata: List<MetadataChildInput>? = null,
    val state: CollectionWorkflowInput? = null,
    val public: Boolean = false,
    val publicList: Boolean = false,
    val publicSupplementary: Boolean = false,
    val searchable: Boolean = true,
    val recommendable: Boolean = true,
) {

    fun toCollection(json: Json): Collection {
        var attrs = attributes
        if (attrs !is JsonObject) {
            attrs = JsonObject(mapOf("published" to JsonPrimitive(System.currentTimeMillis())))
        } else {
            if (attrs.jsonObject["published"] == null) {
                attrs = JsonObject(attrs.jsonObject + JsonObject(mapOf("published" to JsonPrimitive(System.currentTimeMillis()))))
            }
        }
        return Collection(
            name = name,
            description = description,
            languageTag = languageTag ?: "en",
            type = collectionType ?: CollectionType.STANDARD,
            locked = locked ?: false,
            attributes = attrs,
            itemsLocked = itemsLocked ?: false,
            templateMetadataId = templateMetadataId,
            templateMetadataVersion = templateMetadataVersion,
            workflowStateId = "pending",
            systemAttributes = systemAttributes,
            labels = labels ?: emptyList(),
            ready = ready,
            enabled = true,
            ordering = json.encodeToJsonElement(
                ListSerializer(OrderingInput.serializer()),
                ordering ?: emptyList()
            ),
            deleteWorkflowId = deleteWorkflowId,
            public = public,
            publicList = publicList,
            publicSupplementary = publicSupplementary,
            searchable = searchable,
            recommendable = recommendable,
        )
    }

    fun toCollection(existing: Collection, json: Json): Collection {
        if (attributes !is JsonObject) error("must be an object")
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
            description = description,
            type = collectionType ?: CollectionType.STANDARD,
            locked = locked ?: false,
            attributes = attrs,
            itemsLocked = itemsLocked ?: false,
            templateMetadataId = templateMetadataId ?: existing.templateMetadataId,
            templateMetadataVersion = templateMetadataVersion ?: existing.templateMetadataVersion,
            systemAttributes = systemAttributes,
            labels = labels ?: emptyList(),
            ready = ready ?: existing.ready,
            ordering = json.encodeToJsonElement(
                ListSerializer(OrderingInput.serializer()),
                ordering ?: emptyList()
            ),
            deleteWorkflowId = deleteWorkflowId,
            public = existing.public,
            publicList = existing.publicList,
            publicSupplementary = existing.publicSupplementary,
            searchable = existing.searchable,
            recommendable = existing.recommendable,
        )
    }
}
