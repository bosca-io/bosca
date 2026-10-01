package bosca.pipelines.node

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * A value flowing along a pipeline edge, paired with the **explicit** [KSerializer] that can
 * (de)serialize it.
 *
 * The serializer travels *with* the object — a node never receives a bare `Any`; it receives a
 * [PipelineValue]. This is the linchpin of the GraalVM-native, zero-reflection design: any
 * downstream node can bridge a typed value to JSON (or back) with the carried serializers, so there
 * is never a reflective `serializer<T>()` lookup.
 *
 * When a typed value is converted to its JSON form ([toJson]), the original type's serializer is
 * retained as [originSerializer] so a later node (e.g. `JSON → Typed`) can convert back via
 * [decodeOrigin]. JSON produced by *shape-changing* nodes (JSONata, ObjectsToMap) has no origin —
 * the original type no longer describes it.
 *
 * [PipelineValue] itself is a runtime carrier and is never serialized/stored.
 */
class PipelineValue(
    val value: Any?,
    val serializer: KSerializer<Any?>,
    /**
     * The output port this value leaves through, for routing nodes (e.g. a Condition node emits on
     * `"true"` or `"false"`). The executor only propagates a ported value along edges whose
     * `sourcePort` matches; `null` (the default) flows along every outbound edge.
     */
    val port: String? = null,
    /** The original typed value's serializer, retained across [toJson] for conversion back. */
    val originSerializer: KSerializer<Any?>? = null,
    /**
     * A catalogued interface or base type explicitly declared by a Cast node. The concrete value
     * and serializer remain unchanged; this is the pipeline-level static type used for slot checks.
     */
    private val declaredTypeName: String? = null,
) {
    /** Serialize [value] to a [JsonElement] using its own explicit [serializer] (no reflection). */
    fun encode(json: Json): JsonElement = json.encodeToJsonElement(serializer, value)

    val asString: String?
        get() = if (value is JsonElement) {
            value.jsonPrimitive.contentOrNull
        } else {
            value.toString()
        }

    /**
     * The serial name of this value's **typed** origin — the carried [serializer], or the
     * [originSerializer] retained across a [toJson]. Null when the value is plain JSON with no typed
     * origin (produced by JSONata/ObjectsToMap), so a slot's specific-`type` constraint cannot be
     * applied to it. Reflection-free: reads the explicit serializer's descriptor.
     */
    val typeName: String?
        get() = when {
            declaredTypeName != null -> declaredTypeName
            originSerializer != null -> originSerializer.descriptor.serialName
            value is JsonElement -> null
            else -> serializer.descriptor.serialName
        }

    /** The same value emitted on a named output [port] (routing nodes). */
    fun onPort(port: String): PipelineValue =
        PipelineValue(value, serializer, port, originSerializer, declaredTypeName)

    /**
     * Re-declares this value as an assignable catalogued type without replacing its concrete
     * serializer. Callers must first verify that the runtime value implements the requested type.
     */
    fun declaredAs(typeName: String): PipelineValue =
        PipelineValue(value, serializer, port, originSerializer, typeName)

    /**
     * Decodes this value to [T] by bridging through its JSON form: the value is encoded with its own
     * carried [serializer] and re-decoded with [target]. Reflection-free, and shape-tolerant: it
     * works whether the inbound value is already the typed object, its JSON form, or plain JSON
     * (e.g. a JSONata result) that satisfies [target]'s shape. JSON `null` decodes to `null`; a
     * value that does not match [target] throws [kotlinx.serialization.SerializationException],
     * failing the node like a hand-written decode.
     *
     * This is the **primitive the KSP-generated `<Node>Serializer` codecs call** — node code should
     * not call it directly: use the node's generated `deserialize` (strict) or `deserializePartial`
     * (dry-run tolerant) so slot decoding stays declared in one place, the annotation.
     */
    fun <T> decode(target: KSerializer<T>, json: Json): T? {
        val element = encode(json)
        if (element is JsonNull) return null
        return json.decodeFromJsonElement(target, element)
    }

    /**
     * This value in JSON form, retaining the typed serializer as [originSerializer] so the
     * conversion is reversible. Already-JSON values pass through unchanged (keeping any origin).
     */
    fun toJson(json: Json): PipelineValue {
        if (value is JsonElement) return this
        return PipelineValue(
            encode(json),
            JSON_SERIALIZER,
            port,
            originSerializer ?: serializer,
            declaredTypeName,
        )
    }

    /**
     * Converts a JSON-form value back to its original type via [originSerializer], or `null` when
     * there is no origin to decode to (plain JSON, or shape-changed by JSONata/ObjectsToMap).
     */
    fun decodeOrigin(json: Json): PipelineValue? {
        val origin = originSerializer ?: return null
        val element = value as? JsonElement ?: return null
        return PipelineValue(
            json.decodeFromJsonElement(origin, element),
            origin,
            port,
            declaredTypeName = declaredTypeName,
        )
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private val JSON_SERIALIZER = JsonElement.serializer() as KSerializer<Any?>

        @Suppress("UNCHECKED_CAST")
        fun <T> of(value: T, serializer: KSerializer<T>): PipelineValue =
            PipelineValue(value, serializer as KSerializer<Any?>)

        // Delegate to the explicit-serializer overload so `serializer()` resolves against its
        // `KSerializer<T>` parameter (intrinsified to T's compiled serializer at the call site) rather
        // than the `KSerializer<Any?>` constructor slot — which would infer `serializer<Any?>()` and
        // throw "Serializer for class kotlin.Any is not found" at run time.
        inline fun <reified T> of(value: T): PipelineValue =
            of(value, serializer())

        /** Wrap a value that is already a [JsonElement] (no origin type). */
        fun ofJson(value: JsonElement): PipelineValue = PipelineValue(value, JSON_SERIALIZER)
    }
}
