package bosca.pipelines.node

import bosca.pipelines.annotation.SlotKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Enforces a node input slot's declared constraints against the value arriving on it — the runtime
 * counterpart of the static [NodeInputSlot] declarations the KSP registrar builds from `@InputSlot`.
 *
 * Native-safe: each value is encoded to a [JsonElement] via its own carried serializer (no
 * reflective `serializer<T>()`), and the checks run over that element exactly as
 * [JsonSchemaValidator] does. The three constraint forms are independent and ANDed — a slot may
 * pin a coarse [SlotKind], demand a specific object [NodeInputSlot.type] (serial name), and/or
 * require a [NodeInputSlot.schema] shape; whichever are set must all hold.
 *
 * Returns every violation (not just the first) so a run/dry-run can report the full picture.
 */
object SlotValidator {

    /** Canonical 8-4-4-4-12 hexadecimal UUID, the content a [SlotKind.UUID] string must match. */
    private val UUID_REGEX =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * All violations across [slots] given the node's gathered [inputs]. A single-slot node's sole
     * inbound value is matched regardless of the edge's port name (single-input nodes read
     * [NodeInputs.first]); a multi-slot node matches each slot to the inbound value on the port of
     * the same [NodeInputSlot.name]. A [NodeInputSlot.required] slot with no value is a violation.
     */
    fun validate(slots: List<NodeInputSlot>, inputs: NodeInputs, json: Json): List<String> {
        if (slots.isEmpty()) return emptyList()
        val violations = mutableListOf<String>()
        val single = slots.size == 1
        for (slot in slots) {
            val value = if (single) inputs.first else inputs[slot.name]
            if (value == null) {
                if (slot.required) violations += "input '${slot.name}': required but no value was provided"
                continue
            }
            violations += validate(slot, value, json)
        }
        return violations
    }

    /** All violations of [slot] by the inbound [value], or empty when it conforms. */
    fun validate(slot: NodeInputSlot, value: PipelineValue, json: Json): List<String> {
        val violations = mutableListOf<String>()
        val element = value.encode(json)
        kindViolation(slot.kind, element)?.let { violations += "input '${slot.name}': $it" }
        slot.type?.let { type ->
            // On an ARRAY slot the declared type constrains the ELEMENTS: a typed list's own serial
            // name is its container class ("kotlin.collections.ArrayList"), never the element type.
            val violation =
                if (slot.kind == SlotKind.ARRAY) elementTypeViolation(type, value) else typeViolation(type, value)
            violation?.let { violations += "input '${slot.name}': $it" }
        }
        slot.schema?.let { schema ->
            JsonSchemaValidator.validate(element, schema).forEach { violations += "input '${slot.name}' $it" }
        }
        return violations
    }

    private fun kindViolation(kind: SlotKind, value: JsonElement): String? {
        val ok = when (kind) {
            SlotKind.ANY -> true
            SlotKind.STRING -> value is JsonPrimitive && value.isString
            SlotKind.INTEGER -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
            SlotKind.NUMBER -> value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null
            SlotKind.BOOLEAN -> value is JsonPrimitive && !value.isString && value.content.toBooleanStrictOrNull() != null
            SlotKind.UUID -> value is JsonPrimitive && value.isString && UUID_REGEX.matches(value.content)
            SlotKind.OBJECT -> value is JsonObject
            SlotKind.ARRAY -> value is JsonArray
        }
        return if (ok) null else "expected ${kind.name.lowercase()} but got ${describe(value)}"
    }

    private fun typeViolation(type: String, value: PipelineValue): String? {
        // A plain-JSON value (e.g. JSONata output) carries no typed origin — it cannot be disproved
        // against a specific type, so the type check is skipped and any declared schema is relied on.
        val actual = value.typeName ?: return null
        return if (actual == type) null else "expected an object of type '$type' but got '$actual'"
    }

    /**
     * The ARRAY-slot counterpart of [typeViolation]: checks the typed list's ELEMENT serial name
     * (via the carried serializer's element descriptor) against the slot's declared element [type].
     * Plain-JSON arrays (no typed origin) and non-list typed values (a single element fanned in,
     * whose own [typeViolation]-style name is compared directly) are handled the same lenient way.
     */
    private fun elementTypeViolation(type: String, value: PipelineValue): String? {
        if (value.typeName == null) return null // plain JSON — rely on kind + schema
        val descriptor = (value.originSerializer ?: value.serializer).descriptor
        val actual = if (descriptor.kind == StructureKind.LIST) {
            // A nullable element serializer's serial name carries a trailing '?' — not part of the type.
            descriptor.getElementDescriptor(0).serialName.removeSuffix("?")
        } else {
            descriptor.serialName
        }
        return if (actual == type) null else "expected items of type '$type' but got '$actual'"
    }

    private fun describe(value: JsonElement): String = when (value) {
        is JsonObject -> "an object"
        is JsonArray -> "an array"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            value.isString -> "a string"
            value.content.toBooleanStrictOrNull() != null -> "a boolean"
            value.content.toLongOrNull() != null -> "an integer"
            value.content.toDoubleOrNull() != null -> "a number"
            else -> "a primitive"
        }
    }
}
