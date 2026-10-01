package bosca.pipelines.node

import kotlinx.serialization.Serializable

/** Canvas coordinate of a node in the pipeline editor; round-trips with the stored graph. */
@Serializable
data class NodePosition(val x: Double = 0.0, val y: Double = 0.0)
