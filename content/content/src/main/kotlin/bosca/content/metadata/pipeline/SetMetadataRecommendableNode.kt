package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
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
 * Persists whether an inbound [Metadata] may be returned as a recommendation candidate through
 * [MetadataService.setRecommendable]. The refreshed metadata is passed through for chaining; a dry run
 * records the intended action without changing the entity.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Metadata Recommendable",
    description = "Sets whether metadata may be returned as a recommendation.",
    group = "Content",
    subgroup = "Metadata",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Metadata",
            type = Metadata::class,
            description = "A Metadata (e.g. from Get Metadata).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Metadata::class,
            typeLabel = "Metadata",
            description = "The metadata after the update, for chaining.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "recommendable",
            control = SettingControl.BOOLEAN,
            label = "Recommendable",
            default = "true",
            description = "On allows the metadata in recommendations; off excludes it.",
        ),
    ],
)
@Serializable
@SerialName("metadata.setRecommendable")
class SetMetadataRecommendableNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** True allows the metadata in recommendations; false excludes it. */
    val recommendable: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = inputs.first
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "setMetadataRecommendable")
            put("recommendable", recommendable)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadata = SetMetadataRecommendableNodeSerializer.deserialize(context, inputs).`in`
        val label = name.ifBlank { id }
        val service = provide<MetadataService>()
        service.setRecommendable(metadata.id, recommendable)
        val fresh = service.getById(metadata.id)
            ?: error("Set Metadata Recommendable node '$label': metadata ${metadata.id} not found after update")
        return SetMetadataRecommendableNodeSerializer.serialize(fresh)
    }
}
