package bosca.pipelines.node

import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.model.PipelineEdge

/**
 * Connect-time (graph) counterpart of [SlotValidator]: checks that every edge feeding a node's
 * typed input slot comes from a source whose **declared** output kind is compatible with the slot's
 * kind. This catches an incompatible wiring (e.g. a UUID slot fed by an integer-producing node) when
 * the pipeline is saved or pushed, before it ever runs.
 *
 * Pure and reasoning only over static [NodeDescriptor] metadata (no registry, no DI), so it is
 * trivially unit-testable. A slot declaring [SlotKind.ANY] accepts anything. But a source with a
 * dynamic/unknown output — [SlotKind.ANY] (a JSONata node) or a source with no typed descriptor at
 * all (the Input node) — is treated as unknown and **refused** by a typed slot: if you can't prove
 * the value is the right kind, it isn't. To feed such a slot, route the value through a node that
 * declares the matching output (e.g. a Get Id node for a UUID slot).
 *
 * Beyond the coarse [SlotKind], an `OBJECT` slot that names a specific [NodeInputSlot.type] (e.g. a
 * "Get Document" node that needs a `Metadata`, not just any object) is **type-pinned**: the source
 * must declare the matching type. A different object type — or an untyped object source — is refused,
 * so "object" alone is never accepted where a `Collection` is required.
 *
 * A source's kind/type is resolved from the **specific port** the edge leaves ([NodeDescriptor.outputs]):
 * matched by [PipelineEdge.sourcePort], or — for an edge off a single-output node's anonymous handle
 * (no sourcePort) — the node's lone non-error output. A node with no declared outputs has an implicit
 * output of unknown kind.
 */
object SlotConnectionValidator {

    /**
     * All connection violations across [edges]. [nodeKeyById] maps each node id to its type key
     * (`@SerialName`); [descriptors] is the node-type metadata keyed by that same key. [declaredOutputs]
     * holds a source node's instance-level output declaration (a JSONata node the builder annotated),
     * keyed by node id — it overrides the (dynamic) type descriptor so a typed slot can accept it.
     * [declaredInputs] is the target-side mirror ([HasDeclaredInputs]): a node instance's per-slot
     * type requirement (an email template node's payload contract), which turns the matched slot into
     * a type-pinned OBJECT slot.
     */
    fun validate(
        nodeKeyById: Map<String, String>,
        edges: List<PipelineEdge>,
        descriptors: Map<String, NodeDescriptor>,
        declaredOutputs: Map<String, HasDeclaredOutput> = emptyMap(),
        declaredInputs: Map<String, HasDeclaredInputs> = emptyMap(),
    ): List<String> {
        val violations = mutableListOf<String>()
        for (edge in edges) {
            val target = nodeKeyById[edge.target]?.let { descriptors[it] } ?: continue
            if (target.inputs.isEmpty()) continue
            val slot = matchSlot(target, edge.targetPort)?.let { matched ->
                declaredInputs[edge.target]?.declaredInputTypes?.get(matched.name)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { requiredType(matched, it) } ?: matched
            } ?: continue
            if (slot.kind == SlotKind.ANY) continue
            // Resolve the source's effective output kind/type — threading through routing nodes, which
            // pass their input straight through (see [resolveOutput]). A source that resolves to an
            // unknown value (the Input node, a routing node with nothing wired in) is ANY, which a typed
            // slot refuses below ("unknown isn't a match").
            val (sourceKind, sourceType) = resolveOutput(edge.source, edge.sourcePort, nodeKeyById, descriptors, declaredOutputs, edges)
            // A routing node (Condition/Switch) is a transparent passthrough. If the flowing type can't
            // be pinned (resolves to ANY), trust it into an OBJECT/ARRAY slot — payloads are the natural
            // thing to route, and its branch is the author's assertion point. A SCALAR slot (string,
            // uuid, number, …) is NOT trusted: those come from specific producers, so an unpinned value
            // into one is caught here rather than failing at run time. Known-but-wrong types fall through.
            val sourceIsRouting = (nodeKeyById[edge.source]?.let { descriptors[it] })?.category == NodeCategory.ROUTE
            if (sourceIsRouting && sourceKind == SlotKind.ANY && (slot.kind == SlotKind.OBJECT || slot.kind == SlotKind.ARRAY)) continue
            if (!isAssignable(sourceKind, slot.kind)) {
                violations += violation(edge, slot, sourceKind)
                continue
            }
            // Kind matches; a slot that demands a specific object type needs the source to produce it —
            // UNLESS the source's element type genuinely can't be pinned statically: an untyped value
            // routed through a routing node, or an untyped ARRAY from a generic array transform (e.g.
            // Flatten, whose element type is only known once its input flows). Those are trusted into the
            // typed slot; the runtime SlotValidator still enforces the actual value. A scalar/uuid slot,
            // and a known-but-wrong type, are still refused.
            // TODO: the backend COULD derive an array transform's element type precisely (it has the same
            //  graph the editor derives from), but its type model is flat — (SlotKind, element-serial-name)
            //  can express "array of X" but not "array of array of X", and the validator is deliberately
            //  domain-agnostic (it keys off NodeCategory, never a node's identity). The precise fix is a
            //  declarative output-derivation rule on the descriptor (like ROUTE is for passthrough) — e.g.
            //  outputDerivation = FLATTEN | WRAP | PASSTHROUGH — so array-transform outputs derive
            //  generically (and a wrong element type is rejected). Until then, trust an untyped array here.
            val requiredType = slot.type
            val unpinnableSource = sourceType == null && (sourceIsRouting || sourceKind == SlotKind.ARRAY)
            if (!requiredType.isNullOrBlank() && sourceType != requiredType && !unpinnableSource) {
                violations += typeViolation(edge, slot, sourceType)
            }
        }
        return violations
    }

    /**
     * A source node's effective output kind/type for the wire leaving [sourcePort]. A **routing** node
     * ([NodeCategory.ROUTE] — Condition, Switch) emits its input unchanged on the taken branch, so its
     * output is whatever feeds its input: resolve recursively through the edge into it (a routing node
     * has a single input). Any other node uses its instance declaration, then its named port, then its
     * node-level output. A routing node with nothing wired in — or a cycle — resolves to ANY.
     */
    private fun resolveOutput(
        nodeId: String,
        sourcePort: String?,
        nodeKeyById: Map<String, String>,
        descriptors: Map<String, NodeDescriptor>,
        declaredOutputs: Map<String, HasDeclaredOutput>,
        edges: List<PipelineEdge>,
        visited: Set<String> = emptySet(),
    ): Pair<SlotKind, String?> {
        if (nodeId in visited) return SlotKind.ANY to null
        val descriptor = nodeKeyById[nodeId]?.let { descriptors[it] }
        if (descriptor?.category == NodeCategory.ROUTE) {
            val inbound = edges.firstOrNull { it.target == nodeId } ?: return SlotKind.ANY to null
            return resolveOutput(inbound.source, inbound.sourcePort, nodeKeyById, descriptors, declaredOutputs, edges, visited + nodeId)
        }
        val declared = declaredOutputs[nodeId]
        // The wire's port: matched by name, or — when an edge leaves a single-output node's anonymous
        // handle (no sourcePort) — the lone non-error output. No outputs at all = an implicit ANY output.
        val outs = descriptor?.outputs.orEmpty()
        val port = outs.firstOrNull { it.name == sourcePort } ?: outs.singleOrNull { !it.error }
        val kind = declared?.declaredOutputKind?.takeIf { it != SlotKind.ANY }
            ?: port?.kind?.takeIf { it != SlotKind.ANY }
            ?: SlotKind.ANY
        val type = declared?.declaredOutputType?.ifBlank { null } ?: port?.type
        return kind to type
    }

    /**
     * The [slot] refined by an instance-level [type] requirement: a type-pinned OBJECT slot whose
     * label is the required type itself, so violation messages name the actual contract.
     */
    private fun requiredType(slot: NodeInputSlot, type: String): NodeInputSlot = NodeInputSlot(
        name = slot.name,
        typeLabel = type,
        kind = SlotKind.OBJECT,
        description = slot.description,
        type = type,
        schema = slot.schema,
        required = slot.required,
        structure = slot.structure,
    )

    /** The friendly noun for what a slot wants — its label, falling back to its kind name. */
    private fun NodeInputSlot.expectsLabel(): String = typeLabel.ifBlank { kind.name.lowercase() }

    /** A connection violation message — clearer when the source is an unknown/[SlotKind.ANY] value. */
    private fun violation(edge: PipelineEdge, slot: NodeInputSlot, sourceKind: SlotKind): String {
        val expects = slot.kind.name.lowercase()
        return if (sourceKind == SlotKind.ANY) {
            val hint = if (slot.kind == SlotKind.UUID) " — route it through a node that outputs a uuid (e.g. a Get Id node)" else ""
            "input '${slot.name}' of node '${edge.target}' expects $expects but its source '${edge.source}' " +
                "produces an unknown value$hint"
        } else {
            "input '${slot.name}' of node '${edge.target}' expects $expects but is fed " +
                "${sourceKind.name.lowercase()} from '${edge.source}'"
        }
    }

    /** A type-pin violation: the kind matched but the specific object type did not. */
    private fun typeViolation(edge: PipelineEdge, slot: NodeInputSlot, sourceType: String?): String {
        val expects = slot.expectsLabel()
        return if (sourceType.isNullOrBlank()) {
            "input '${slot.name}' of node '${edge.target}' expects a $expects but its source '${edge.source}' " +
                "produces an untyped object — feed it a $expects"
        } else {
            "input '${slot.name}' of node '${edge.target}' expects a $expects but is fed " +
                "${sourceType.substringAfterLast('.')} from '${edge.source}'"
        }
    }

    /** The slot an edge targets: a single-slot node ignores the port; a multi-slot node matches by name. */
    private fun matchSlot(target: NodeDescriptor, targetPort: String?): NodeInputSlot? =
        if (target.inputs.size == 1) target.inputs.first()
        else target.inputs.firstOrNull { it.name == targetPort }

    /** Whether a value of [source] kind can flow into a [target]-kind slot, with the natural widenings. */
    private fun isAssignable(source: SlotKind, target: SlotKind): Boolean = when {
        target == SlotKind.ANY || source == target -> true
        target == SlotKind.NUMBER && source == SlotKind.INTEGER -> true // an integer is a number
        // A UUID does NOT widen into a string — it's an identifier, not arbitrary text. To feed a UUID
        // into a string slot, convert it explicitly with a To String node.
        else -> false
    }
}
