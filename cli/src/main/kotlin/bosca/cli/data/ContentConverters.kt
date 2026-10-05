package bosca.cli.data

import bosca.cli.data.model.*
import bosca.graphql.gen.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared converters for transforming manifest definition types and
 * raw string enums into GraphQL input types. Used by [DataInstaller],
 * MCP content tools, and CLI content commands.
 */
object ContentConverters {

    fun String.toAttributeType(): AttributeType = when (uppercase()) {
        "STRING" -> AttributeType.STRING
        "INT" -> AttributeType.INT
        "FLOAT" -> AttributeType.FLOAT
        "DATE" -> AttributeType.DATE
        "DATETIME", "DATE_TIME" -> AttributeType.DATE_TIME
        "METADATA" -> AttributeType.METADATA
        "COLLECTION" -> AttributeType.COLLECTION
        "PROFILE" -> AttributeType.PROFILE
        else -> error("Unknown AttributeType: $this")
    }

    fun String.toAttributeUiType(): AttributeUiType = when (uppercase()) {
        "INPUT" -> AttributeUiType.INPUT
        "TEXTAREA" -> AttributeUiType.TEXTAREA
        "IMAGE" -> AttributeUiType.IMAGE
        "FILE" -> AttributeUiType.FILE
        "COLLECTION" -> AttributeUiType.COLLECTION
        "METADATA" -> AttributeUiType.METADATA
        "PROFILE" -> AttributeUiType.PROFILE
        else -> error("Unknown AttributeUiType: $this")
    }

    fun String.toAttributeLocation(): AttributeLocation = when (uppercase()) {
        "ITEM" -> AttributeLocation.ITEM
        "RELATIONSHIP" -> AttributeLocation.RELATIONSHIP
        else -> error("Unknown AttributeLocation: $this")
    }

    fun String.toContainerType(): DocumentTemplateContainerType = when (uppercase()) {
        "STANDARD" -> DocumentTemplateContainerType.STANDARD
        "BIBLE" -> DocumentTemplateContainerType.BIBLE
        "METADATA" -> DocumentTemplateContainerType.METADATA
        else -> error("Unknown DocumentTemplateContainerType: $this")
    }

    fun String.toCollectionType(): CollectionType = when (uppercase()) {
        "STANDARD" -> CollectionType.STANDARD
        "FOLDER" -> CollectionType.FOLDER
        "ROOT" -> CollectionType.ROOT
        "QUEUE" -> CollectionType.QUEUE
        "SYSTEM" -> CollectionType.SYSTEM
        else -> error("Unknown CollectionType: $this")
    }

    fun String.toDataType(): DataType = when (uppercase()) {
        "ATTRIBUTES" -> DataType.ATTRIBUTES
        "TABLE" -> DataType.TABLE
        else -> error("Unknown DataType: $this")
    }

    fun String.toGuideType(): GuideType = when (uppercase()) {
        "LINEAR" -> GuideType.LINEAR
        "LINEAR_PROGRESS" -> GuideType.LINEAR_PROGRESS
        "CALENDAR" -> GuideType.CALENDAR
        "CALENDAR_PROGRESS" -> GuideType.CALENDAR_PROGRESS
        else -> error("Unknown GuideType: $this")
    }

    fun String.toOrder(): Order = when (uppercase()) {
        "ASCENDING" -> Order.ASCENDING
        "DESCENDING" -> Order.DESCENDING
        else -> error("Unknown Order: $this")
    }

    fun TemplateAttributeDefinition.toTemplateAttributeInput() = TemplateAttributeInput(
        key = key,
        name = name,
        description = description,
        type = type.toAttributeType(),
        ui = ui.toAttributeUiType(),
        list = list,
        location = location?.toAttributeLocation(),
        configuration = configuration,
    )

    fun ContainerDefinition.toContainerInput() = DocumentTemplateContainerInput(
        id = id,
        name = name,
        description = description,
        containerType = type.toContainerType(),
        supplementaryKey = supplementaryKey,
        workflows = emptyList(),
        filters = filters,
    )

    fun OrderingDefinition.toOrderingInput() = OrderingInput(
        field = field,
        location = location?.toAttributeLocation(),
        order = order.toOrder(),
        path = path,
        type = type?.toAttributeType(),
    )

    fun templateEditorAttributes(templateType: String) = JsonObject(
        mapOf(
            "editor.type" to JsonPrimitive("Template"),
            "template.type" to JsonPrimitive(templateType),
        )
    )
}
