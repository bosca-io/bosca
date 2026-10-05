package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingOption
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.HasDeclaredOutput
import bosca.pipelines.node.JsonSchemaValidator
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import com.dashjoin.jsonata.Jsonata
import com.dashjoin.jsonata.json.Json as JsonataJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Shapes/extracts JSON with a JSONata [expression] — the universal JSON last-mile.
 *
 * The inbound value is bridged to JSON via its own carried serializer (so it works whether upstream
 * produced a typed object or JSON already), the JSON is handed to dashjoin JSONata (which operates on
 * plain Java objects — `Map`/`List`/`String`/`Number`/`Boolean`/`null`), and the result is walked
 * back into a [JsonElement]. All native-safe: no reflective serializer lookup, no `kotlin-reflect`.
 *
 * Expressions can reference `$eventCreated` — when the run's triggering occurrence happened
 * ([PipelineContext.inputCreated], ISO-8601; `$toMillis($eventCreated)` for arithmetic).
 *
 * Its output is dynamic by type, so a typed input slot refuses it at connect time. A builder who
 * knows the shape declares it via [outputKind]/[outputType]/[outputSchema] ([HasDeclaredOutput]): the
 * declared kind/type lets the connection validator accept the wire, and a declared [outputSchema]
 * is enforced against the result here at run time (a mismatch fails the run — no silent wrong shape).
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "JSONata",
    description = "Shapes or extracts JSON with a JSONata expression — map fields, build objects, compute values.",
    group = "Core",
    subgroup = "Transform",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Value",
            description = "The value the JSONata expression runs against (bridged to JSON); the expression's result becomes the output.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "expression", control = SettingControl.CODE, label = "JSONata expression",
            language = "jsonata", required = true, placeholder = "profile.email",
            description = "Maps, extracts, or builds JSON from the inbound value. Reference \$eventCreated for the run's occurrence time.",
        ),
        SettingSlot(
            name = "outputKind", control = SettingControl.ENUM, label = "Declared output type", default = "ANY",
            description = "A JSONata result is dynamic, so a typed input refuses it. Declare what this expression returns and the connection is allowed.",
            options = [
                SettingOption("ANY", "Any (undeclared)"), SettingOption("STRING", "Text (string)"),
                SettingOption("OBJECT", "Object"), SettingOption("ARRAY", "List (array)"),
                SettingOption("UUID", "Id (UUID)"), SettingOption("INTEGER", "Integer"),
                SettingOption("NUMBER", "Number"), SettingOption("BOOLEAN", "Boolean"),
            ],
        ),
        SettingSlot(
            name = "outputType", control = SettingControl.REFERENCE, reference = ReferenceSource.TYPE,
            label = "Element / object type (optional)",
            visibleWhenSetting = "outputKind", visibleWhenEquals = "OBJECT,ARRAY",
            description = "The specific type this expression returns — a catalogued type or a reusable shape. For a List, this is the element type.",
        ),
        SettingSlot(
            name = "outputSchema", control = SettingControl.SCHEMA, label = "Validate the result against a schema",
            description = "Optional JSON-Schema enforced at run time — the run fails if the result doesn't match it. No fields = no validation.",
        ),
    ],
)
@Serializable
@SerialName("jsonata")
class JsonataNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val expression: String,
    /** The coarse kind this expression returns, declared so a typed input slot accepts it; ANY = undeclared. */
    val outputKind: SlotKind = SlotKind.ANY,
    /** The specific object type (serial name) this expression returns, for a type-pinned object slot; "" = none. */
    val outputType: String = "",
    /** A JSON-Schema-subset the result must satisfy; the run fails if it doesn't. `null` = none. */
    val outputSchema: JsonElement? = null,
    override val position: NodePosition = NodePosition(),
) : TransformNode(), HasDeclaredOutput {

    /** Declaring a specific [outputType] implies an OBJECT output even when [outputKind] is left ANY. */
    override val declaredOutputKind: SlotKind
        get() = if (outputKind == SlotKind.ANY && outputType.isNotBlank()) SlotKind.OBJECT else outputKind
    override val declaredOutputType: String get() = outputType
    override val declaredOutputSchema: JsonElement? get() = outputSchema

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = inputs.first ?: error("JSONata node '$id' requires an input")
        val javaInput = JsonataJson.parseJson(input.encode(context.json).toString())
        val jsonata = Jsonata.jsonata(expression)
        val frame = jsonata.createFrame()
        frame.bind("eventCreated", context.inputCreated.toString())
        val result = jsonata.evaluate(javaInput, frame)
        val output = toJsonElement(result)
        // A declared output schema is a promise about the result's shape — enforce it so a wrong
        // shape fails here rather than flowing downstream (and failing later, or silently).
        outputSchema?.let { schema ->
            val violations = JsonSchemaValidator.validate(output, schema)
            check(violations.isEmpty()) {
                "JSONata node '${name.ifBlank { id }}' output does not satisfy its declared schema: " +
                    violations.joinToString("; ")
            }
        }
        return PipelineValue.ofJson(output)
    }

    private fun toJsonElement(value: Any?): JsonElement = when {
        value == null || value === Jsonata.NULL_VALUE -> JsonNull
        value is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) -> put(k.toString(), toJsonElement(v)) }
        }
        value is List<*> -> buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        value is Boolean -> JsonPrimitive(value)
        value is Number -> JsonPrimitive(value)
        value is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
