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
 * Persists whether an inbound [Collection] may be returned as a recommendation candidate through
 * [CollectionService.setRecommendable]. The refreshed collection is passed through for chaining; a dry
 * run records the intended action without changing the entity.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Collection Recommendable",
    description = "Sets whether a collection may be returned as a recommendation.",
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
            name = "recommendable",
            control = SettingControl.BOOLEAN,
            label = "Recommendable",
            default = "true",
            description = "On allows the collection in recommendations; off excludes it.",
        ),
    ],
)
@Serializable
@SerialName("collection.setRecommendable")
class SetCollectionRecommendableNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** True allows the collection in recommendations; false excludes it. */
    val recommendable: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = inputs.first
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "setCollectionRecommendable")
            put("recommendable", recommendable)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val collection = SetCollectionRecommendableNodeSerializer.deserialize(context, inputs).`in`
        val label = name.ifBlank { id }
        val service = provide<CollectionService>()
        service.setRecommendable(collection.id, recommendable)
        val fresh = service.getById(collection.id)
            ?: error("Set Collection Recommendable node '$label': collection ${collection.id} not found after update")
        return SetCollectionRecommendableNodeSerializer.serialize(fresh)
    }
}
