package bosca.pipelines.node

/**
 * Runtime (non-serialized) keyed access to a node's inbound edge values — each a [PipelineValue]
 * (value + its explicit serializer), named by the target port.
 *
 * A single-input node reads [first]; a multi-input node (e.g. an `ObjectsToMap`) reads each
 * operator-named port via [get].
 */
class NodeInputs(private val byPort: Map<String, PipelineValue>) {
    val isEmpty: Boolean get() = byPort.isEmpty()

    /** The sole/first inbound value — the common case for single-input nodes. */
    val first: PipelineValue? get() = byPort.values.firstOrNull()

    operator fun get(port: String): PipelineValue? = byPort[port]

    fun require(port: String): PipelineValue =
        byPort[port] ?: error("Pipeline node input '$port' is missing")

    fun asMap(): Map<String, PipelineValue> = byPort
}
