package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
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
 * Resolver node contributed by `content`: loads the [Metadata] for an inbound metadata [UUID] via
 * [MetadataService], under the run's principal. Resolves the latest version. Output carries
 * `Metadata.serializer()` so downstream nodes bridge to JSON natively.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Metadata",
    description = "Loads the full Metadata for a metadata id.",
    group = "Content",
    subgroup = "Metadata",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Metadata id",
            description = "The metadata's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Metadata::class,
            typeLabel = "Metadata",
            description = "The full Metadata.",
        ),
    ],
)
@Serializable
@SerialName("metadata.fromEvent")
class MetadataEventToMetadataNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadataId = MetadataEventToMetadataNodeSerializer.deserialize(context, inputs).`in`
        val metadata = provide<MetadataService>().getById(metadataId)
            ?: error("Metadata node '${name.ifBlank { id }}': metadata $metadataId not found")
        return MetadataEventToMetadataNodeSerializer.serialize(metadata)
    }
}
