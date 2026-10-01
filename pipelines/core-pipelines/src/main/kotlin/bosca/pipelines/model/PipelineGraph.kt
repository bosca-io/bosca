package bosca.pipelines.model

import bosca.pipelines.node.PipelineNode
import kotlinx.serialization.Serializable

/**
 * The serializable graph body of a [Pipeline] — the part stored as a single `jsonb` column. Split
 * out from the metadata so the polymorphic node list (de)serializes as one unit through the engine's
 * aggregated node `SerializersModule`.
 */
@Serializable
data class PipelineGraph(
    val nodes: List<PipelineNode> = emptyList(),
    val edges: List<PipelineEdge> = emptyList(),
    /**
     * Editor-only visual group frames drawn around sets of nodes (see [NodeGroup]). The executor
     * ignores this — it rides in the stored graph JSON so a saved pipeline keeps its grouping/labels.
     */
    val groups: List<NodeGroup> = emptyList(),
)
