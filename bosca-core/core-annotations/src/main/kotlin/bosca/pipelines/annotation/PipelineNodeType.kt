package bosca.pipelines.annotation

/**
 * Marks a `bosca.pipelines.node.PipelineNode` subclass for KSP auto-registration. The core-ksp
 * generator emits, per module, a `PipelineNodeSerializers` provider that registers this node's
 * **explicit** serializer into the polymorphic node `SerializersModule` (so it (de)serializes under
 * GraalVM native — no reflective subclass scanning) and carries its palette metadata
 * ([category]/[label]). Any module can contribute a node type just by annotating it.
 *
 * The annotated class must be `@Serializable` with a stable `@SerialName` — that serial name is the
 * node-type key (the polymorphic discriminator and the editor palette key).
 *
 * Named `PipelineNodeType` (not `PipelineNode`) so it never collides with the `PipelineNode` base
 * class that `InputNode`/`OutputNode` extend directly.
 */
@Target(AnnotationTarget.CLASS)
annotation class PipelineNodeType(
    val category: NodeCategory = NodeCategory.TRANSFORM,
    val label: String = "",
    /** One-sentence palette tooltip: what the node does, written for the pipeline editor user. */
    val description: String = "",
    /**
     * Top-level organizational group the node belongs to in the palette and node browser — the
     * domain that contributes it (e.g. `"WorkOps"`, `"Content"`, `"Core"`, `"Integrations"`).
     * Orthogonal to [category], which stays the *functional* kind (drawn as the node's chrome).
     * `""` (the default) = ungrouped; the UI falls back to bucketing by [category].
     */
    val group: String = "",
    /**
     * Second-level grouping under [group] (e.g. `"Releases"`, `"Environments"`, `"Moderation"`).
     * `""` = none; the node sits directly under its [group]. Meaningless without a [group].
     */
    val subgroup: String = "",
    /**
     * Typed input slots (ports) this node accepts, declared in port order. Empty (the default) means
     * the node imposes no per-slot constraints. The executor enforces each slot against its inbound
     * value at run time; the editor projects them to flag incompatible connections.
     */
    val inputs: Array<InputSlot> = [],
    /**
     * The node's output ports ([OutputSlot]), declaring what it emits — kind, optional specific
     * [OutputSlot.type], label and description — the producing-side mirror of [inputs]. The connection
     * validator and the editor's "Produces" section read them.
     *
     * A node with a **single** output declares one slot (conventionally named `"out"`); the editor
     * draws it as the node's anonymous output handle. A node with no [outputs] has a single implicit
     * output of unknown kind ([SlotKind.ANY]) — only a downstream slot's own [InputSlot.schema] can
     * validate it at run time. Two or more slots (or any [OutputSlot.error] port) render as named,
     * wireable handles; an emitted-but-unwired error port fails the run.
     */
    val outputs: Array<OutputSlot> = [],
    /**
     * The node's editable settings ([SettingSlot]), in display order — each names a key the node reads
     * from its graph JSON and the editor control that edits it. Empty (the default) means the node has
     * no configuration. The Studio inspector renders these generically, so a node type's whole form is
     * data-driven; a new node type needs no UI change to be fully editable.
     */
    val settings: Array<SettingSlot> = [],
)
