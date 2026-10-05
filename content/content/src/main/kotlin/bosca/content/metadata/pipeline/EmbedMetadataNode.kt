package bosca.content.metadata.pipeline

import bosca.content.embedding.service.EmbeddingService
import bosca.content.metadata.model.Metadata
import bosca.di.provide
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

/**
 * Action node contributed by `content`: computes and stores a semantic embedding for an inbound [Metadata],
 * feeding the recommender's content tower. The platform's default embedding pipeline wires it after a Get
 * Metadata resolver: `Event → Get Id → Get Metadata → this`.
 *
 * The embedded text is the same body text the search index uses, so semantic similarity aligns with search.
 * [EmbeddingService.embed] returns false when embeddings are disabled, the text is blank, or a changing
 * source could not be stored after its bounded current-snapshot retry. These cases are handled here rather
 * than gating the node (the pipeline's existence is the coarse enable; the feature flag is the fine one).
 * When embeddings are enabled, a provider failure (embedder unreachable, error status, or unparseable
 * response) THROWS and fails this node/run — we surface embedding failures, we do not silently skip them.
 *
 * The input Metadata passes through so the node can chain. Under [PipelineContext.dryRun] it records the
 * intended action and skips the embed (no HTTP call, no write).
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Embed Metadata",
    description = "Computes a semantic embedding for a metadata item and stores it for recommendations.",
    group = "Content",
    subgroup = "Metadata",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Metadata",
            type = Metadata::class,
            description = "A Metadata (e.g. from Get Metadata) to embed.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Metadata::class,
            typeLabel = "Metadata",
            description = "The metadata, passed through.",
        ),
    ],
)
@Serializable
@SerialName("metadata.embed")
class EmbedMetadataNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // A dry run traces whatever is wired so far, so a missing required input must not fail it —
        // hence the lenient deserializePartial, not deserialize.
        val metadata = EmbedMetadataNodeSerializer.deserializePartial(context, inputs).`in`
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "embed")
            put("metadataId", metadata?.id?.toString() ?: "")
        })
        return inputs.first
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadata = EmbedMetadataNodeSerializer.deserialize(context, inputs).`in`
        provide<EmbeddingService>().embed(metadata)
        return EmbedMetadataNodeSerializer.serialize(metadata)
    }
}
