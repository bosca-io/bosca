package bosca.pipelines.node

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One field of an author-declared object *shape* — a map of typed fields used as a pipeline's Input or
 * Output contract when the value is an object built ad-hoc (e.g. an `Objects → Map` result) rather than a
 * single catalogued type. [type] is a label the editor understands: a catalogued serial name (optionally
 * `[]`-suffixed for a list), `"JSON"`, or a primitive. Purely declarative — carried in the node graph so a
 * For Each or a consuming pipeline sees the shape's fields.
 */
@Serializable
class ShapeField(
    val name: String,
    val type: String,
)

/**
 * The pipeline's entry. The consumer feeds the typed input as a [PipelineValue] (value + its
 * serializer, which the consumer already has — e.g. the event serializer from the Event Catalog);
 * the engine seeds this node so its output is that value. [acceptedType] is the fully-qualified type
 * the pipeline accepts, used by consumers to filter which pipelines may run for a given input
 * (assignability via the `java.lang.Class` hierarchy — never `kotlin-reflect`).
 *
 * [acceptedType] may be an event FQDN, a specific catalogued object type (e.g. a `ReleaseProjectVersion`
 * fed by a Run Pipeline node inside a ForEach), or — for a pipeline meant to be *called* (manual run, Run
 * Pipeline node) rather than event-triggered — the [JSON_TYPE] sentinel with an optional [schema] (the
 * [JsonSchemaValidator] subset). For any specific type (event or object) the node [HasDeclaredOutput]: it
 * declares its output as that type, so downstream typed slots are validated and can be introspected — a
 * JSON/blank input stays untyped ([SlotKind.ANY]).
 */
@PipelineNodeType(
    category = NodeCategory.INPUT,
    label = "Input",
    description = "Where the pipeline starts — receives the triggering event, a specific typed value, or supplied JSON input and passes it downstream.",
)
@Serializable
@SerialName("input")
class InputNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val acceptedType: String,
    /** Accepted-shape declaration for [JSON_TYPE] inputs; null = any JSON. */
    val schema: JsonElement? = null,
    /** The declared object shape's fields when [acceptedType] is [SHAPE_TYPE]; empty otherwise. */
    val fields: List<ShapeField> = emptyList(),
    override val position: NodePosition = NodePosition(),
) : PipelineNode(), HasDeclaredOutput {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first

    /** A pure passthrough — a dry run carries the seed value downstream, no side effect to skip. */
    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? = execute(context, inputs)

    /** True when the input is an author-declared object shape ([SHAPE_TYPE], fields in [fields]). */
    private val isShape: Boolean get() = acceptedType == SHAPE_TYPE

    /** True when [acceptedType] is a specific type (an event or catalogued object) rather than JSON/shape/blank. */
    private val isTyped: Boolean get() = acceptedType.isNotBlank() && acceptedType != JSON_TYPE && !isShape

    /** A specific typed input or a shape is an OBJECT downstream; JSON/blank stays untyped. */
    override val declaredOutputKind: SlotKind get() = if (isTyped || isShape) SlotKind.OBJECT else SlotKind.ANY
    override val declaredOutputType: String get() = if (isTyped) acceptedType else ""

    companion object {
        /**
         * [acceptedType] sentinel for pipelines that accept plain JSON instead of a catalogued
         * event type. Never matches an event FQDN, so JSON-input pipelines cannot be triggered —
         * they run via the Run Pipeline node or the manual run mutation.
         */
        const val JSON_TYPE = "JSON"

        /** [acceptedType] sentinel for an author-declared object shape — the typed fields live in [fields]. */
        const val SHAPE_TYPE = "SHAPE"
    }
}

/**
 * The pipeline's optional exit. The [PipelineValue] on its inbound edge becomes the pipeline's
 * result. A pipeline without an [OutputNode] is a pure side-effect flow — and a Run Pipeline node
 * invoking it produces no output, skipping its downstream nodes.
 *
 * [outputType] and [schema] are the pipeline's **declared** output contract, shown to authors
 * wiring this pipeline as a sub-pipeline. Declared, not derived: most outputs are shaped by
 * JSONata, whose result type isn't statically knowable.
 */
@PipelineNodeType(
    category = NodeCategory.OUTPUT,
    label = "Output",
    description = "Marks the pipeline's result — whatever arrives here becomes the run's output.",
)
@Serializable
@SerialName("output")
class OutputNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** Declared output type label — `"JSON"`, `"SHAPE"` (typed fields in [fields]), a fully-qualified type name, or empty = undeclared. */
    val outputType: String = "",
    /** Declared output shape for JSON outputs (the [JsonSchemaValidator] subset); null = undeclared. */
    val schema: JsonElement? = null,
    /** The declared object shape's fields when [outputType] is `"SHAPE"`; empty otherwise. */
    val fields: List<ShapeField> = emptyList(),
    override val position: NodePosition = NodePosition(),
) : PipelineNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first

    /** A pure passthrough — a dry run carries the inbound value through as the result, no side effect to skip. */
    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? = execute(context, inputs)
}
