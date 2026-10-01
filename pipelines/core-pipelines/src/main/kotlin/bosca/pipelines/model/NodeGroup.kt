package bosca.pipelines.model

import kotlinx.serialization.Serializable

/**
 * A visual **group frame** drawn around a set of nodes in the editor (WORKOPS-SPEC). Purely editor
 * metadata — the executor never reads it; it rides in the stored graph JSON alongside [nodes][PipelineGraph.nodes]
 * and [edges][PipelineGraph.edges] so a saved pipeline preserves how its author grouped and labelled
 * the canvas. Members ([nodeIds]) move with the frame and can be hidden when it is [collapsed].
 *
 * Positions/sizes are absolute canvas coordinates (the same space as [NodePosition]); the editor
 * re-parents members to the frame at render time for the move-together behaviour.
 */
@Serializable
data class NodeGroup(
    val id: String,
    /** The author's label describing what the grouped nodes do. */
    val label: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 240.0,
    val height: Double = 160.0,
    /** When true the editor hides the member nodes and their internal edges, showing just the frame. */
    val collapsed: Boolean = false,
    /** Optional accent colour (CSS string) for the frame, so groups can be colour-coded. */
    val color: String? = null,
    /** The ids of the nodes contained in this frame — they move with it and hide when it collapses. */
    val nodeIds: List<String> = emptyList(),
)
