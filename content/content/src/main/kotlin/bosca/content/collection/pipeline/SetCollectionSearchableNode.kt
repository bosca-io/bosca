package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
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
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action node contributed by `content`: controls whether an inbound [Collection] appears in search
 * results via [CollectionService.setSearchable]. Persists the collection's `searchable` flag (unlike
 * the routing-only Visibility Gate). Passes the *refreshed* collection through for chaining; under
 * [PipelineContext.dryRun] it records the intended action and mutates nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Collection Searchable",
    description = "Sets whether a collection appears in search results.",
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
            kind = SlotKind.OBJECT,
            type = Collection::class,
            typeLabel = "Collection",
            description = "The collection after the update, for chaining.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "searchable", control = SettingControl.BOOLEAN, label = "Searchable", default = "true",
            description = "On includes the collection in search results; off excludes it.",
        ),
    ],
)
@Serializable
@SerialName("collection.setSearchable")
class SetCollectionSearchableNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** True includes the collection in search results; false excludes it. */
    val searchable: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Reads the raw inbound value only — a dry run traces whatever is wired so far, so a missing
        // required input must not fail it.
        val input = inputs.first
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "setCollectionSearchable")
            put("searchable", searchable)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val collection = SetCollectionSearchableNodeSerializer.deserialize(context, inputs).`in`
        val label = name.ifBlank { id }
        val service = provide<CollectionService>()
        service.setSearchable(collection.id, searchable)
        val fresh = service.getById(collection.id)
            ?: error("Set Collection Searchable node '$label': collection ${collection.id} not found after update")
        return SetCollectionSearchableNodeSerializer.serialize(fresh)
    }
}
