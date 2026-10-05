package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.service.MetadataService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `content`: resolves a [Metadata]'s [MetadataSupplementary] entries
 * for an inbound metadata, via [MetadataService]. Output carries an explicit
 * `ListSerializer(MetadataSupplementary…)`.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Supplementaries",
    description = "Loads a metadata item's supplementary entries.",
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
            kind = SlotKind.ARRAY,
            type = MetadataSupplementary::class,
            typeLabel = "List of MetadataSupplementary",
            description = "The metadata's supplementaries.",
        ),
    ],
)
@Serializable
@SerialName("metadata.supplementaries")
class MetadataSupplementariesNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadata = MetadataSupplementariesNodeSerializer.deserialize(context, inputs).`in`
        val supplementaries = provide<MetadataService>().getSupplementary(metadata.id)
        return MetadataSupplementariesNodeSerializer.serialize(supplementaries)
    }
}
