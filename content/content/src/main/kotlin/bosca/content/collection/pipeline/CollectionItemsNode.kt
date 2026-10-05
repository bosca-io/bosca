package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionItem
import bosca.content.collection.service.CollectionService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `content`: resolves a [Collection]'s items for an inbound collection,
 * via [CollectionService]. Items come back in the collection's server-side ordering. Settings narrow
 * the fetch: [state], [contentTypes], [languageTag], and [limit] (from the start of the collection's
 * ordering).
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Collection Items",
    description = "Loads a collection's items in the collection's order, optionally filtered by state, content type, language, and limit.",
    group = "Content",
    subgroup = "Collections",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Collection",
            type = Collection::class,
            description = "A Collection (e.g. from Get Collection).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.ARRAY,
            type = CollectionItem::class,
            typeLabel = "List of CollectionItem",
            description = "The collection's items, in its server-side order.",
        ),
    ],
    settings = [
        SettingSlot(name = "state", control = SettingControl.TEXT, label = "State", placeholder = "Any state"),
        SettingSlot(name = "limit", control = SettingControl.INTEGER, label = "Limit", default = "100", placeholder = "100"),
        SettingSlot(name = "contentTypes", control = SettingControl.LIST, label = "Content types", placeholder = "Any — comma-separated to narrow"),
        SettingSlot(name = "languageTag", control = SettingControl.TEXT, label = "Language", placeholder = "Any language"),
    ],
)
@Serializable
@SerialName("collection.items")
class CollectionItemsNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val state: String? = null,
    val limit: Int = 100,
    val contentTypes: List<String>? = null,
    val languageTag: String? = null,
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val collection = CollectionItemsNodeSerializer.deserialize(context, inputs).`in`
        val items = provide<CollectionService>().getItems(
            id = collection.id,
            state = state,
            offset = 0,
            limit = limit,
            languageTag = languageTag,
            contentTypes = contentTypes,
        )
        return CollectionItemsNodeSerializer.serialize(items)
    }
}
