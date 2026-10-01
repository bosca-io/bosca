package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataAIService
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
 * Action node contributed by `content`: generates an AI summary of an inbound [Metadata]'s document
 * body and stores it in the metadata's `attributes` under [summaryAttribute].
 *
 * The input is a [Metadata] object, whose version is honored.
 * Summarization **reuses the platform [MetadataAIService]
 * contract** (`description`) — which extracts the stored document text and applies the configured
 * description prompt/model — so no model/prompt config, AI dependency, or LLM client leaks into the node.
 * The summary is deep-merged into `attributes[summaryAttribute]` (default `"aiSummary"`, distinct from a
 * publisher's own `attributes["summary"]`, so it augments rather than overwrites). The node passes its
 * input through so it can chain. Under [PipelineContext.dryRun] it records the intended action and skips
 * **both** the LLM call and the write.
 *
 * Generic by design — it knows nothing about feeds; any consumer configures a pipeline that contains it.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Summarize with AI",
    description = "Generates an AI summary of a metadata's document body and stores it in attributes.",
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
            description = "The input Metadata, passed through unchanged for chaining.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "summaryAttribute", control = SettingControl.TEXT, label = "Summary attribute", mono = true, default = "aiSummary",
            placeholder = "aiSummary",
            description = "The metadata attributes key the AI summary is stored under (kept distinct from a publisher's own summary). Reuses the platform's description prompt/model; the inbound Metadata passes through unchanged.",
        ),
    ],
)
@Serializable
@SerialName("metadata.summarize")
class SummarizeNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The metadata `attributes` key to store the AI summary under, distinct from a publisher's own summary. */
    val summaryAttribute: String = "aiSummary",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // A dry run traces whatever is wired so far, so a missing required input must not fail it —
        // hence the lenient deserializePartial, not deserialize.
        val metadata = SummarizeNodeSerializer.deserializePartial(context, inputs).`in`

        // Gate before the LLM call: a dry run records intent and incurs no model cost or write.
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "summarize")
            put("metadataId", metadata?.id?.toString() ?: "")
            put("attribute", summaryAttribute)
        })
        return inputs.first
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // The out port passes the inbound PipelineValue through unchanged, so keep the raw value.
        val input = inputs.first
        val metadata = SummarizeNodeSerializer.deserialize(context, inputs).`in`

        val summary = provide<MetadataAIService>().description(metadata, document = null)
        if (summary.isBlank()) return input

        provide<MetadataService>().mergeAttributes(metadata, buildJsonObject { put(summaryAttribute, summary) })
        return input
    }
}
