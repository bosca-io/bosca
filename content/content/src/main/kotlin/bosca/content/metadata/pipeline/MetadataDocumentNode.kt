package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Document
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.DocumentService
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
import kotlinx.serialization.json.JsonNull

/**
 * Resolver node contributed by `content`: loads the [Document] body for an inbound [Metadata] via
 * [DocumentService], honoring the metadata object's own version. Output carries `Document.serializer()`.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Document",
    description = "Loads the document body for a metadata, at the metadata's version.",
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
            type = Document::class,
            typeLabel = "Document",
            description = "The metadata's document body.",
        ),
    ],
)
@Serializable
@SerialName("metadata.document")
class MetadataDocumentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadata = MetadataDocumentNodeSerializer.deserialize(context, inputs).`in`
        val document = provide<DocumentService>().getDocument(metadata.id, metadata.version)
            ?: return PipelineValue.ofJson(JsonNull)
        return MetadataDocumentNodeSerializer.serialize(document)
    }
}
