package bosca.content.attributes.model

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.content.metadata.model.DataTemplateAttribute
import bosca.content.metadata.model.DocumentTemplateAttribute
import bosca.content.metadata.model.GuideTemplateAttribute
import bosca.content.timeevent.model.TimeEventTypeAttribute
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Unified wrapper that presents a single attribute interface over the various
 * domain-specific template attribute types. Each instance holds exactly one
 * non-null backing attribute (document, collection, guide, data, or
 * time-event-type).
 *
 * Note: [metadataId] and [version] are intentionally nullable because
 * [TimeEventTypeAttribute] attributes are not scoped to a specific metadata
 * record — they belong to a time-event type definition instead.
 */
@Serializable
class TemplateAttribute(
    val documentAttribute: DocumentTemplateAttribute? = null,
    val collectionAttribute: CollectionTemplateAttribute? = null,
    val guideAttribute: GuideTemplateAttribute? = null,
    val dataAttribute: DataTemplateAttribute? = null,
    val timeEventTypeAttribute: TimeEventTypeAttribute? = null
) {

    val metadataId: UUID?
        get() = documentAttribute?.metadataId ?: collectionAttribute?.metadataId ?: guideAttribute?.metadataId ?: dataAttribute?.metadataId

    val version: Int?
        get() = documentAttribute?.version ?: collectionAttribute?.version ?: guideAttribute?.version ?: dataAttribute?.version

    val key: String
        get() = documentAttribute?.key ?: collectionAttribute?.key ?: guideAttribute?.key ?: dataAttribute?.key ?: timeEventTypeAttribute?.key ?: error("attribute is null")

    val name: String
        get() = documentAttribute?.name ?: collectionAttribute?.name ?: guideAttribute?.name ?: dataAttribute?.name ?: timeEventTypeAttribute?.name ?: error("attribute is null")

    val description: String
        get() = documentAttribute?.description ?: collectionAttribute?.description ?: guideAttribute?.description ?: dataAttribute?.description ?: timeEventTypeAttribute?.description ?: error("attribute is null")

    val supplementaryKey: String?
        get() = documentAttribute?.supplementaryKey ?: collectionAttribute?.supplementaryKey ?: guideAttribute?.supplementaryKey ?: dataAttribute?.supplementaryKey ?: timeEventTypeAttribute?.supplementaryKey

    @Contextual
    val configuration: JsonElement?
        get() = documentAttribute?.configuration ?: collectionAttribute?.configuration ?: guideAttribute?.configuration ?: dataAttribute?.configuration ?: timeEventTypeAttribute?.configuration

    val location: AttributeLocation
        get() = collectionAttribute?.location ?: AttributeLocation.ITEM

    val type: AttributeType
        get() = documentAttribute?.type ?: collectionAttribute?.type ?: guideAttribute?.type ?: dataAttribute?.type ?: timeEventTypeAttribute?.type ?: error("attribute is null")

    val ui: AttributeUiType
        get() = documentAttribute?.ui ?: collectionAttribute?.ui ?: guideAttribute?.ui ?: dataAttribute?.ui ?: timeEventTypeAttribute?.ui ?: error("attribute is null")

    val list: Boolean
        get() = documentAttribute?.list ?: collectionAttribute?.list ?: guideAttribute?.list ?: dataAttribute?.list ?: timeEventTypeAttribute?.list ?: error("attribute is null")

    val sort: Int
        get() = documentAttribute?.sort ?: collectionAttribute?.sort ?: guideAttribute?.sort ?: dataAttribute?.sort ?: timeEventTypeAttribute?.sort ?: error("attribute is null")

    @Contextual
    val tools: JsonElement?
        get() = documentAttribute?.tools ?: collectionAttribute?.tools ?: guideAttribute?.tools ?: dataAttribute?.tools ?: timeEventTypeAttribute?.tools
}
