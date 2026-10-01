package bosca.pipelines.builtin

import bosca.communications.service.BmlMessageTemplateRendererService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.HasDeclaredOutput
import bosca.pipelines.node.JsonSchemaValidator
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.pipelines.service.PipelineShapeService
import bosca.serialization.SerializerCache
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Casts the inbound value **to a declared type** ([outputType]) — the pipeline analog of a language
 * cast: the value's content is unchanged; its *type* is reinterpreted. Use it to turn plain JSON (a
 * JSONata result, a webhook payload) into a catalogued platform type, or a value statically typed as
 * an interface into a concrete implementation, so downstream type-pinned slots accept the wire and
 * decode it as that type.
 *
 * For a **catalogued type**, the cast is real, not just declared: the target's explicitly-registered
 * serializer is resolved by serial name from [SerializerCache] (populated at startup by each module's
 * KSP registrar — no reflection) and the inbound value is decoded through it, so the emitted value
 * *is* the target type ([PipelineValue.typeName] reports it) and a value that does not fit the target
 * fails the node — the graph's `ClassCastException`. A value already of the target type passes
 * through. For a **named shape** (`shape:<name>`), there is no Kotlin type to decode into: the value
 * must be an object carrying the shape's declared fields, and flows on as JSON declared as the shape.
 * For an **email template payload** (`email:<project>/<template>`), the value is validated against
 * the hosted template's serializer-derived payload schema — so a payload wired toward a Send Email
 * Template node fails here, with the contract violations, rather than at send time.
 *
 * The node [HasDeclaredOutput] with the chosen type, so the
 * [bosca.pipelines.node.SlotConnectionValidator] and the editor accept the wire into a type-pinned
 * slot at save time. Distinct from [JsonToSerializableNode], which only reverses an upstream
 * `Serializable → JSON` via the carried origin — Cast targets any catalogued type from any value.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Cast",
    description = "Casts the inbound value to a chosen type — plain JSON to a typed object, or an interface value to a concrete type.",
    group = "Core",
    subgroup = "Transform",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.ANY,
            typeLabel = "Value",
            description = "The value to cast — plain JSON, or a typed value to reinterpret as the target type.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "outputType", control = SettingControl.REFERENCE, reference = ReferenceSource.TYPE,
            label = "Cast to", required = true, mono = true,
            description = "The target type. A catalogued type is decoded — the value becomes that type, and a mismatch fails the run. A reusable shape or an email template payload is checked structurally.",
        ),
    ],
)
@Serializable
@SerialName("cast")
class CastNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /**
     * The target: a type's serial name, `shape:<name>` for a reusable named shape, or
     * `email:<project>/<template>` for a hosted email template's payload contract; "" = unconfigured.
     */
    val outputType: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode(), HasDeclaredOutput {

    override val declaredOutputKind: SlotKind
        get() = if (outputType.isBlank()) SlotKind.ANY else SlotKind.OBJECT
    override val declaredOutputType: String get() = outputType

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = CastNodeSerializer.deserialize(context, inputs)
        check(outputType.isNotBlank()) { "Cast node '${label()}': no target type is configured" }
        return when {
            outputType.startsWith(SHAPE_PREFIX) -> castToShape(context, input.`in`)
            outputType.startsWith(EMAIL_PREFIX) -> castToEmailPayload(context, input.`in`)
            else -> castToType(context, input.`in`)
        }
    }

    /** Decode the value through the target type's registered serializer — the emitted value IS the target. */
    private fun castToType(context: PipelineContext, value: PipelineValue): PipelineValue {
        // A cast to the value's own type is the identity — nothing to decode.
        if (value.typeName == outputType) return value
        val targetClass = SerializerCache.classFor(outputType)
        if (targetClass != null && value.value != null && targetClass.isInstance(value.value)) {
            return value.declaredAs(outputType)
        }
        @Suppress("UNCHECKED_CAST")
        val serializer = SerializerCache.get(outputType) as? KSerializer<Any?>
            ?: if (targetClass == null) {
                error("Cast node '${label()}': unknown type '$outputType' — not a registered catalogued type")
            } else {
                error(
                    "Cast node '${label()}': the inbound value does not implement " +
                        outputType.substringAfterLast('.'),
                )
            }
        val decoded = try {
            value.decode(serializer, context.json)
        } catch (e: SerializationException) {
            error("Cast node '${label()}': cannot cast the inbound value to ${outputType.substringAfterLast('.')} — ${e.message}")
        } ?: error("Cast node '${label()}': cannot cast null to ${outputType.substringAfterLast('.')}")
        return PipelineValue(decoded, serializer)
    }

    /**
     * A shape has no Kotlin type to decode into — the cast checks the value is an object carrying the
     * shape's declared fields and flows it on as JSON, with the shape declared as this node's output.
     */
    private suspend fun castToShape(context: PipelineContext, value: PipelineValue): PipelineValue {
        val shapeName = outputType.removePrefix(SHAPE_PREFIX)
        val shape = provide<PipelineShapeService>().getByName(shapeName)
            ?: error("Cast node '${label()}': unknown shape '$shapeName'")
        val element = value.encode(context.json)
        val obj = element as? JsonObject
            ?: error("Cast node '${label()}': cannot cast ${describe(element)} to shape '$shapeName' — expected an object")
        val missing = shape.fields.filter { it.name !in obj }
        check(missing.isEmpty()) {
            "Cast node '${label()}': the inbound object does not match shape '$shapeName' — " +
                "missing ${missing.joinToString { "'${it.name}'" }}"
        }
        return PipelineValue.ofJson(obj)
    }

    /**
     * An email template's payload contract is a serializer-derived schema, not a Kotlin type — the
     * cast validates the object against it (the same [JsonSchemaValidator] subset the engine uses
     * everywhere), so a bad payload fails here with the violations instead of at send time.
     */
    private suspend fun castToEmailPayload(context: PipelineContext, value: PipelineValue): PipelineValue {
        val reference = outputType.removePrefix(EMAIL_PREFIX)
        val project = reference.substringBefore('/')
        val templateKey = reference.substringAfter('/', "")
        check(project.isNotBlank() && templateKey.isNotBlank()) {
            "Cast node '${label()}': '$outputType' is not a valid email template reference — expected email:<project>/<template>"
        }
        val hosted = provide<BmlMessageTemplateRendererService>().hostedProjects()
            .firstOrNull { it.project == project }
            ?: error("Cast node '${label()}': unknown message project '$project'")
        val template = hosted.templates.firstOrNull { it.key == templateKey }
            ?: error("Cast node '${label()}': message project '$project' has no template '$templateKey'")
        val schema = template.payloadSchema
            ?: error("Cast node '${label()}': template '$reference' takes no payload — nothing to cast to")
        val element = value.encode(context.json)
        val obj = element as? JsonObject
            ?: error("Cast node '${label()}': cannot cast ${describe(element)} to the '$reference' payload — expected an object")
        val violations = JsonSchemaValidator.validate(obj, schema)
        check(violations.isEmpty()) {
            "Cast node '${label()}': the inbound object does not match the '$reference' payload contract — " +
                violations.joinToString("; ")
        }
        return PipelineValue.ofJson(obj)
    }

    private fun describe(element: JsonElement): String = when (element) {
        is JsonArray -> "an array"
        is JsonObject -> "an object"
        else -> "a scalar"
    }

    private fun label(): String = name.ifBlank { id }

    companion object {
        /** Marks a reusable named-shape target, matching how the type catalog stores shapes. */
        private const val SHAPE_PREFIX = "shape:"

        /** Marks a hosted email template's payload contract, matching the type catalog's email entries. */
        private const val EMAIL_PREFIX = "email:"
    }
}
