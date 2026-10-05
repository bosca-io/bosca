package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.BibleService
import bosca.di.provide
import bosca.documents.Content
import bosca.documents.HtmlNode
import bosca.documents.NodeConverter
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Convert HTML to Document Content",
    description = "Converts a raw HTML attribute into a Bosca Document Content",
    group = "Content",
    subgroup = "Metadata",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.STRING,
            typeLabel = "Html",
            description = "A HTML.",
        ),
    ],
    outputs = [
        OutputSlot("content", kind = SlotKind.OBJECT, type = Content::class),
    ]
)
@Serializable
@SerialName("convertToDocument")
class ConvertToDocumentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val content = execute(context, inputs) ?: return null
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "convertToDocument")
            put("inputs", inputs.first?.value?.toString())
        })
        return content
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Input decoding stays hand-written: this node deliberately no-ops (returns null) on a missing
        // or blank input, where the generated deserialize would fail the run.
        val html = inputs.first?.value?.toString() ?: return null
        if (html.isBlank()) return null
        val document = NodeConverter(provide<BibleService>()).convertDocument(HtmlNode(html = html))
        return ConvertToDocumentNodeSerializer.serialize(Content(document = document))
    }

}
