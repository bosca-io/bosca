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
 * Action node contributed by `content`: writes user attributes onto an inbound [Collection]. The
 * `attributes` input (arbitrary JSON, commonly shaped by a JSONata node) is either deep-merged into
 * the existing attributes ([merge] = true, the default) or replaces them wholesale ([merge] = false),
 * via [CollectionService.mergeAttributes] / [CollectionService.setAttributes]. Passes the *refreshed*
 * collection through for chaining; under [PipelineContext.dryRun] it records the intended action and
 * mutates nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Collection Attributes",
    description = "Merges or replaces a collection's attributes from a JSON input.",
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
        InputSlot(
            // ANY (not OBJECT): the attributes are arbitrary JSON, commonly shaped by a JSONata node
            // whose output kind is unknown — a typed slot would refuse that wire.
            name = "attributes",
            typeLabel = "Attributes (JSON)",
            description = "The attributes JSON to merge into or replace the collection's attributes.",
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
            name = "merge", control = SettingControl.BOOLEAN, label = "Merge", default = "true",
            description = "On deep-merges the input into existing attributes; off replaces them wholesale.",
        ),
    ],
)
@Serializable
@SerialName("collection.setAttributes")
class SetCollectionAttributesNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** True deep-merges into existing attributes; false replaces them wholesale. */
    val merge: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Reads the raw inbound value only — a dry run traces whatever is wired so far, so missing
        // required inputs must not fail it.
        val input = inputs["in"]
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "setCollectionAttributes")
            put("merge", merge)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = SetCollectionAttributesNodeSerializer.deserialize(context, inputs)
        val collection = input.`in`
        val label = name.ifBlank { id }
        val attributes = input.attributes.encode(context.json)
        val service = provide<CollectionService>()
        if (merge) service.mergeAttributes(collection.id, attributes) else service.setAttributes(collection.id, attributes)
        val fresh = service.getById(collection.id)
            ?: error("Set Collection Attributes node '$label': collection ${collection.id} not found after update")
        return SetCollectionAttributesNodeSerializer.serialize(fresh)
    }
}
