package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** The graph supplied to a flow editor; node settings remain application-owned data. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FlowGraph(
    @EncodeDefault
    val nodes: List<FlowNode> = emptyList(),
    @EncodeDefault
    val edges: List<FlowEdge> = emptyList(),
    val viewport: FlowViewport? = null,
)
