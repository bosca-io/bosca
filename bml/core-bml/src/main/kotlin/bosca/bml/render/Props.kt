package bosca.bml.render

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Decodes a JSON object — a client's posted island/component state for a server-side **sliver
 * re-render** — back into the `Map<String, Any?>` a generated component's `render`
 * reads its props from. The inverse of [propsToJson].
 *
 * **Native-safe:** uses `parseToJsonElement` (a reflection-free parser; no `serializer<T>()`) and
 * walks the tree to natural Kotlin types — `Int` for integral numbers, then `Long`, then `Double`,
 * `Boolean`, `String`, nested `Map`/`List`, `null` — so `props["count"] as Int` / `as String` /
 * `as List<String>` resolve exactly as they do for a first render. A non-object body yields an empty map.
 */
fun propsFromJson(text: String): Map<String, Any?> {
    if (text.isBlank()) return emptyMap()
    val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    return (element as? JsonObject)?.mapValues { decodeJsonValue(it.value) } ?: emptyMap()
}

private fun decodeJsonValue(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonObject -> element.mapValues { decodeJsonValue(it.value) }
    is JsonArray -> element.map { decodeJsonValue(it) }
    is JsonPrimitive ->
        if (element.isString) element.content
        else element.booleanOrNull ?: element.intOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
}

/**
 * Serializes island props (`:prop="expr"`) to a compact JSON object for the `data-bml-props`
 * attribute; the `@bosca/bml` client runtime parses it back into `ctx.props`.
 *
 * **Native-safe by construction:** hand-written encoding over primitives, strings, [Map], and
 * [Iterable] only — no `kotlinx.serialization` reflection — so it works in the GraalVM native image.
 * Unknown types fall back to their `toString()` as a JSON string. The result still flows through
 * [HtmlWriter.attr], which HTML-escapes the quotes for the attribute.
 */
fun propsToJson(props: Map<String, Any?>): String {
    val sb = StringBuilder()
    encodeObject(sb, props)
    return sb.toString()
}

/**
 * Serializes the explicit inputs carried by a deferred island boundary.
 *
 * Unlike ordinary component props, this request boundary rejects unknown JVM objects instead of
 * falling back to `toString()`. That prevents an accidental object representation from becoming
 * part of a shared page and gives authors a clear failure at the declaration that must be reduced
 * to JSON-shaped public data.
 */
fun deferredPropsToJson(props: Map<String, Any?>): String {
    val sb = StringBuilder()
    encodeDeferredObject(sb, props)
    return sb.toString()
}

private fun encodeDeferredObject(sb: StringBuilder, map: Map<*, *>) {
    sb.append('{')
    var first = true
    for ((key, value) in map) {
        require(key is String) { "Deferred island prop names must be strings" }
        if (!first) sb.append(',')
        first = false
        encodeString(sb, key)
        sb.append(':')
        encodeDeferredValue(sb, value)
    }
    sb.append('}')
}

private fun encodeDeferredArray(sb: StringBuilder, values: Iterable<*>) {
    sb.append('[')
    var first = true
    for (value in values) {
        if (!first) sb.append(',')
        first = false
        encodeDeferredValue(sb, value)
    }
    sb.append(']')
}

private inline fun encodeDeferredTagged(sb: StringBuilder, type: String, value: () -> Unit) {
    sb.append('{')
    encodeString(sb, DEFERRED_TYPE_KEY)
    sb.append(':')
    encodeString(sb, type)
    sb.append(',')
    encodeString(sb, DEFERRED_VALUE_KEY)
    sb.append(':')
    value()
    sb.append('}')
}

/**
 * Maps travel as `[[key, value], …]` pairs rather than a JSON object: the browser re-serializes the
 * props it posts back, and JavaScript objects move integer-like keys (`"2"`, `"10"`) ahead of the
 * others, which would change the iteration order the deferred body renders in.
 */
private fun encodeDeferredMap(sb: StringBuilder, map: Map<*, *>) =
    encodeDeferredTagged(sb, "map") {
        sb.append('[')
        var first = true
        for ((key, value) in map) {
            require(key is String) { "Deferred island map keys must be strings" }
            if (!first) sb.append(',')
            first = false
            sb.append('[')
            encodeString(sb, key)
            sb.append(',')
            encodeDeferredValue(sb, value)
            sb.append(']')
        }
        sb.append(']')
    }

private fun encodeDeferredList(sb: StringBuilder, values: Iterable<*>, type: String = "list") =
    encodeDeferredTagged(sb, type) { encodeDeferredArray(sb, values) }

private fun encodeDeferredValue(sb: StringBuilder, value: Any?) {
    when (value) {
        null -> sb.append("null")
        is Boolean -> sb.append(value)
        is Byte -> encodeDeferredTagged(sb, "byte") { encodeString(sb, value.toString()) }
        is Short -> encodeDeferredTagged(sb, "short") { encodeString(sb, value.toString()) }
        is Int -> sb.append(value)
        is Long -> encodeDeferredTagged(sb, "long") { encodeString(sb, value.toString()) }
        is Float -> if (value.isFinite()) {
            encodeDeferredTagged(sb, "float") { encodeString(sb, value.toString()) }
        } else {
            throw IllegalArgumentException("Deferred island props require finite numbers")
        }
        is Double -> if (value.isFinite()) {
            encodeDeferredTagged(sb, "double") { encodeString(sb, value.toString()) }
        } else {
            throw IllegalArgumentException("Deferred island props require finite numbers")
        }
        is CharSequence -> encodeString(sb, value.toString())
        is Char -> encodeDeferredTagged(sb, "char") { encodeString(sb, value.toString()) }
        is Map<*, *> -> encodeDeferredMap(sb, value)
        is List<*> -> encodeDeferredList(sb, value)
        is BooleanArray -> encodeDeferredList(sb, value.asIterable(), "boolean-array")
        is ByteArray -> encodeDeferredList(sb, value.asIterable(), "byte-array")
        is ShortArray -> encodeDeferredList(sb, value.asIterable(), "short-array")
        is IntArray -> encodeDeferredList(sb, value.asIterable(), "int-array")
        is LongArray -> encodeDeferredList(sb, value.asIterable(), "long-array")
        is FloatArray -> encodeDeferredList(sb, value.asIterable(), "float-array")
        is DoubleArray -> encodeDeferredList(sb, value.asIterable(), "double-array")
        is CharArray -> encodeDeferredList(sb, value.asIterable(), "char-array")
        else -> throw IllegalArgumentException(
            "Deferred island props must be JSON-shaped; '${value::class.qualifiedName}' is unsupported",
        )
    }
}

/** The validated wire request sent by the deferred-island browser runtime. */
data class BmlDeferredRenderRequest(
    val props: Map<String, Any?>,
    val page: String,
    val path: String,
    val query: Map<String, String>,
    val locale: String?,
)

/** A deferred request omitted a required prop or supplied a value with the wrong wire type. */
class InvalidBmlDeferredPropsException(message: String) : IllegalArgumentException(message)

/** Reads one compiler-declared required deferred prop and reports wire mistakes distinctly. */
@Suppress("UNCHECKED_CAST")
inline fun <reified T> requiredDeferredProp(props: Map<String, Any?>, name: String): T {
    if (!props.containsKey(name)) throw InvalidBmlDeferredPropsException("Missing deferred prop '$name'")
    val value = props[name]
    return try {
        val converted: Any? = when (T::class) {
            Byte::class -> when (value) {
                null -> null
                is Byte -> value
                is Short, is Int, is Long -> value.toString().toLongOrNull()
                    ?.takeIf { it in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong() }
                    ?.toByte()
                    ?: invalidDeferredProp(name)
                else -> invalidDeferredProp(name)
            }
            Short::class -> when (value) {
                null -> null
                is Byte, is Short -> (value as Number).toShort()
                is Int, is Long -> value.toString().toLongOrNull()
                    ?.takeIf { it in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong() }
                    ?.toShort()
                    ?: invalidDeferredProp(name)
                else -> invalidDeferredProp(name)
            }
            Int::class -> when (value) {
                null -> null
                is Byte, is Short, is Int -> (value as Number).toInt()
                is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
                    ?: invalidDeferredProp(name)
                else -> invalidDeferredProp(name)
            }
            Long::class -> when (value) {
                null -> null
                is Byte, is Short, is Int, is Long -> (value as Number).toLong()
                else -> invalidDeferredProp(name)
            }
            Float::class -> when (value) {
                null -> null
                is Number -> value.toDouble()
                    .takeIf { it.isFinite() && it in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble() }
                    ?.toFloat()
                    ?: invalidDeferredProp(name)
                else -> invalidDeferredProp(name)
            }
            Double::class -> when (value) {
                null -> null
                is Number -> value.toDouble().takeIf { it.isFinite() } ?: invalidDeferredProp(name)
                else -> invalidDeferredProp(name)
            }
            Char::class -> when (value) {
                null -> null
                is Char -> value
                is String -> value.singleOrNull() ?: invalidDeferredProp(name)
                else -> invalidDeferredProp(name)
            }
            else -> value
        }
        converted as T
    } catch (_: ClassCastException) {
        invalidDeferredProp(name)
    } catch (_: NullPointerException) {
        invalidDeferredProp(name)
    }
}

/**
 * Reads a required deferred prop and validates its complete declared wire type without reflection.
 * The compiler supplies [expectedType], including generic arguments, so a value such as
 * `List<String>` cannot pass as `List<Long>` through JVM type erasure.
 */
inline fun <reified T> requiredDeferredProp(
    props: Map<String, Any?>,
    name: String,
    expectedType: String,
): T {
    val value = requiredDeferredProp<T>(props, name)
    if (!deferredPropMatchesExpectedType(value, expectedType)) invalidDeferredProp(name)
    return value
}

/** Reads an optional compiler-declared deferred prop without evaluating its default unnecessarily. */
inline fun <reified T> deferredPropOrDefault(props: Map<String, Any?>, name: String, default: () -> T): T =
    if (props.containsKey(name)) requiredDeferredProp(props, name) else default()

/** Reads and fully validates an optional compiler-declared deferred prop when it is present. */
inline fun <reified T> deferredPropOrDefault(
    props: Map<String, Any?>,
    name: String,
    expectedType: String,
    default: () -> T,
): T = if (props.containsKey(name)) requiredDeferredProp(props, name, expectedType) else default()

/**
 * Returns whether [expectedType] can be validated after crossing the deferred-render JSON boundary.
 * Deferred props support Kotlin scalar, primitive-array, list, and map types expressed directly;
 * aliases and application-specific types must be expanded by the author before code generation.
 */
fun isSupportedDeferredPropType(expectedType: String): Boolean =
    isSupportedDeferredPropType(expectedType, allowProjection = false)

private fun isSupportedDeferredPropType(expectedType: String, allowProjection: Boolean): Boolean {
    var type = expectedType.trim()
    if (type.startsWith("out ")) {
        if (!allowProjection) return false
        type = type.removePrefix("out ").trimStart()
    }
    if (type.startsWith("in ")) {
        if (!allowProjection) return false
        type = type.removePrefix("in ").trimStart()
    }
    if (type == "*") return allowProjection
    if (type.endsWith('?')) type = type.dropLast(1).trimEnd()
    if (type.isEmpty()) return false

    val genericStart = type.indexOf('<')
    val rawType = deferredRawType((if (genericStart < 0) type else type.substring(0, genericStart)).trim())
        ?: return false
    val arguments = if (genericStart < 0) {
        emptyList()
    } else {
        if (!type.endsWith('>')) return false
        splitDeferredTypeArguments(type.substring(genericStart + 1, type.length - 1)) ?: return false
    }

    return when (rawType) {
        "Any", "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double", "Number",
        "Char", "String", "CharSequence", "BooleanArray", "ByteArray", "ShortArray", "IntArray",
        "LongArray", "FloatArray", "DoubleArray", "CharArray" -> arguments.isEmpty()
        "List", "MutableList" ->
            arguments.size == 1 && isSupportedDeferredPropType(arguments.single(), allowProjection = true)
        "Map", "MutableMap" ->
            arguments.size == 2 && isDeferredStringKeyType(arguments[0]) &&
                isSupportedDeferredPropType(arguments[1], allowProjection = true)
        else -> false
    }
}

private fun isDeferredStringKeyType(expectedType: String): Boolean {
    var type = expectedType.trim()
    if (type.startsWith("out ")) type = type.removePrefix("out ").trimStart()
    if (type.startsWith("in ")) return false
    if (type.endsWith('?')) return false
    return deferredRawType(type) == "String"
}

@PublishedApi
internal fun deferredPropMatchesExpectedType(value: Any?, expectedType: String): Boolean =
    deferredValueHasFiniteNumbers(value) &&
        deferredPropMatchesExpectedType(value, expectedType, allowProjection = false)

private fun deferredValueHasFiniteNumbers(value: Any?): Boolean = when (value) {
    is Float -> value.isFinite()
    is Double -> value.isFinite()
    is FloatArray -> value.all(Float::isFinite)
    is DoubleArray -> value.all(Double::isFinite)
    is List<*> -> value.all(::deferredValueHasFiniteNumbers)
    is Map<*, *> -> value.all { (key, entry) ->
        deferredValueHasFiniteNumbers(key) && deferredValueHasFiniteNumbers(entry)
    }
    else -> true
}

private fun deferredPropMatchesExpectedType(
    value: Any?,
    expectedType: String,
    allowProjection: Boolean,
): Boolean {
    if (!isSupportedDeferredPropType(expectedType, allowProjection)) return false
    var type = expectedType.trim()
    if (type.startsWith("out ")) type = type.removePrefix("out ").trimStart()
    if (type.startsWith("in ")) type = type.removePrefix("in ").trimStart()
    if (type == "*") return allowProjection

    val nullable = type.endsWith('?')
    if (value == null) return nullable
    if (nullable) type = type.dropLast(1).trimEnd()

    val genericStart = type.indexOf('<')
    val rawType = deferredRawType((if (genericStart < 0) type else type.substring(0, genericStart)).trim())
        ?: return false
    val arguments = if (genericStart < 0) {
        emptyList()
    } else {
        if (!type.endsWith('>')) return false
        splitDeferredTypeArguments(type.substring(genericStart + 1, type.length - 1)) ?: return false
    }

    return when (rawType) {
        "Any" -> true
        "Boolean" -> value is Boolean
        "Byte" -> value is Byte
        "Short" -> value is Short
        "Int" -> value is Int
        "Long" -> value is Long
        "Float" -> value is Float
        "Double" -> value is Double
        "Number" -> value is Number
        "Char" -> value is Char
        "String", "CharSequence" -> value is String
        "BooleanArray" -> value is BooleanArray
        "ByteArray" -> value is ByteArray
        "ShortArray" -> value is ShortArray
        "IntArray" -> value is IntArray
        "LongArray" -> value is LongArray
        "FloatArray" -> value is FloatArray
        "DoubleArray" -> value is DoubleArray
        "CharArray" -> value is CharArray
        "List", "MutableList" ->
            value is List<*> && (arguments.isEmpty() || arguments.size == 1 && value.all {
                deferredPropMatchesExpectedType(it, arguments.single(), allowProjection = true)
            })
        "Map", "MutableMap" ->
            value is Map<*, *> && (arguments.isEmpty() || arguments.size == 2 && value.all { (key, entry) ->
                deferredPropMatchesExpectedType(key, arguments[0], allowProjection = true) &&
                    deferredPropMatchesExpectedType(entry, arguments[1], allowProjection = true)
            })
        else -> false
    }
}

/** Resolves only the built-in Kotlin wire types; an application class with the same short name is distinct. */
private fun deferredRawType(type: String): String? {
    if (type.isEmpty()) return null
    if ('.' !in type) return type
    return when (type) {
        "kotlin.Any" -> "Any"
        "kotlin.Boolean" -> "Boolean"
        "kotlin.Byte" -> "Byte"
        "kotlin.Short" -> "Short"
        "kotlin.Int" -> "Int"
        "kotlin.Long" -> "Long"
        "kotlin.Float" -> "Float"
        "kotlin.Double" -> "Double"
        "kotlin.Number", "java.lang.Number" -> "Number"
        "kotlin.Char" -> "Char"
        "kotlin.String", "java.lang.String" -> "String"
        "kotlin.CharSequence", "java.lang.CharSequence" -> "CharSequence"
        "kotlin.BooleanArray" -> "BooleanArray"
        "kotlin.ByteArray" -> "ByteArray"
        "kotlin.ShortArray" -> "ShortArray"
        "kotlin.IntArray" -> "IntArray"
        "kotlin.LongArray" -> "LongArray"
        "kotlin.FloatArray" -> "FloatArray"
        "kotlin.DoubleArray" -> "DoubleArray"
        "kotlin.CharArray" -> "CharArray"
        "kotlin.collections.List" -> "List"
        "kotlin.collections.MutableList" -> "MutableList"
        "kotlin.collections.Map" -> "Map"
        "kotlin.collections.MutableMap" -> "MutableMap"
        else -> null
    }
}

private fun splitDeferredTypeArguments(arguments: String): List<String>? {
    val result = mutableListOf<String>()
    var depth = 0
    var start = 0
    arguments.forEachIndexed { index, char ->
        when (char) {
            '<' -> depth++
            '>' -> {
                depth--
                if (depth < 0) return null
            }
            ',' -> if (depth == 0) {
                val argument = arguments.substring(start, index).trim()
                if (argument.isEmpty()) return null
                result += argument
                start = index + 1
            }
        }
    }
    if (depth != 0) return null
    val last = arguments.substring(start).trim()
    if (last.isEmpty()) return null
    result += last
    return result
}

@PublishedApi
internal fun invalidDeferredProp(name: String): Nothing =
    throw InvalidBmlDeferredPropsException("Invalid deferred prop '$name'")

/** Parses a deferred-render request without reflection, preserving native-image compatibility. */
fun parseDeferredRenderRequest(text: String): BmlDeferredRenderRequest? {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val propsObject = root["props"] as? JsonObject ?: return null
    val props = runCatching { propsObject.mapValues { decodeDeferredJsonValue(it.value, depth = 1) } }.getOrNull()
        ?: return null
    val page = (root["page"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!page.startsWith('/') || page.startsWith("//") || '?' in page || '#' in page) return null
    val path = (root["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!path.startsWith('/') || path.startsWith("//") || '?' in path || '#' in path) return null
    val queryObject = root["query"] as? JsonObject ?: return null
    val query = buildMap {
        for ((key, value) in queryObject) {
            val string = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            put(key, string)
        }
    }
    val locale = when (val value = root["locale"]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content ?: return null
        else -> return null
    }
    return BmlDeferredRenderRequest(props, page, path, query, locale)
}

/**
 * Decodes one posted prop value. [depth] is bounded so a small, deeply nested request body fails as
 * an invalid request (422) instead of exhausting the stack in this decoder or in the later type walk.
 */
private fun decodeDeferredJsonValue(element: JsonElement, depth: Int): Any? {
    if (depth > MAX_DEFERRED_DEPTH) error("deferred prop nesting exceeds $MAX_DEFERRED_DEPTH levels")
    return when (element) {
        is JsonNull -> null
        is JsonArray -> element.map { decodeDeferredJsonValue(it, depth + 1) }
        is JsonObject -> {
            val type = (element[DEFERRED_TYPE_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type == null) {
                element.mapValues { decodeDeferredJsonValue(it.value, depth + 1) }
            } else {
                decodeDeferredTagged(type, element[DEFERRED_VALUE_KEY] ?: error("missing deferred value"), depth)
            }
        }
        is JsonPrimitive ->
            if (element.isString) element.content
            else element.booleanOrNull
                ?: element.intOrNull
                ?: element.longOrNull
                ?: element.doubleOrNull?.takeIf(Double::isFinite)
                ?: error("invalid deferred number")
    }
}

private fun decodeDeferredTagged(type: String, value: JsonElement, depth: Int): Any? {
    fun element(item: JsonElement): Any? = decodeDeferredJsonValue(item, depth + 1)
    return when (type) {
        "byte" -> taggedString(value).toByte()
        "short" -> taggedString(value).toShort()
        "long" -> taggedString(value).toLong()
        "float" -> taggedString(value).toFloat().takeIf(Float::isFinite)
            ?: error("non-finite deferred float")
        "double" -> taggedString(value).toDouble().takeIf(Double::isFinite)
            ?: error("non-finite deferred double")
        "char" -> taggedString(value).single()
        "map" -> decodeDeferredMap(value, depth)
        "list" -> taggedArray(value).map(::element)
        "boolean-array" -> taggedArray(value).map { element(it) as Boolean }.toBooleanArray()
        "byte-array" -> taggedArray(value).map { element(it) as Byte }.toByteArray()
        "short-array" -> taggedArray(value).map { element(it) as Short }.toShortArray()
        "int-array" -> taggedArray(value).map { element(it) as Int }.toIntArray()
        "long-array" -> taggedArray(value).map { element(it) as Long }.toLongArray()
        "float-array" -> taggedArray(value).map { element(it) as Float }.toFloatArray()
        "double-array" -> taggedArray(value).map { element(it) as Double }.toDoubleArray()
        "char-array" -> taggedArray(value).map { element(it) as Char }.toCharArray()
        else -> error("unknown deferred value type")
    }
}

/**
 * A map arrives as `[[key, value], …]` pairs in insertion order. The earlier JSON-object form is
 * still accepted so shells cached at the edge before a deploy keep working until they expire.
 */
private fun decodeDeferredMap(value: JsonElement, depth: Int): Map<String, Any?> = when (value) {
    is JsonArray -> LinkedHashMap<String, Any?>(value.size).also { map ->
        for (entry in value) {
            val pair = entry as? JsonArray ?: error("invalid deferred map entry")
            if (pair.size != 2) error("invalid deferred map entry")
            val key = taggedString(pair[0])
            if (map.containsKey(key)) error("duplicate deferred map key")
            map[key] = decodeDeferredJsonValue(pair[1], depth + 1)
        }
    }
    is JsonObject -> value.mapValues { decodeDeferredJsonValue(it.value, depth + 1) }
    else -> error("invalid deferred map")
}

private fun taggedString(value: JsonElement): String =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid deferred scalar")

private fun taggedArray(value: JsonElement): JsonArray = value as? JsonArray ?: error("invalid deferred array")

private const val DEFERRED_TYPE_KEY = "\$bml"
private const val DEFERRED_VALUE_KEY = "value"
private const val MAX_DEFERRED_DEPTH = 64

private fun encodeObject(sb: StringBuilder, map: Map<*, *>) {
    sb.append('{')
    var first = true
    for ((k, v) in map) {
        if (!first) sb.append(',')
        first = false
        encodeString(sb, k.toString())
        sb.append(':')
        encodeValue(sb, v)
    }
    sb.append('}')
}

private fun encodeArray(sb: StringBuilder, items: Iterable<*>) {
    sb.append('[')
    var first = true
    for (item in items) {
        if (!first) sb.append(',')
        first = false
        encodeValue(sb, item)
    }
    sb.append(']')
}

private fun encodeValue(sb: StringBuilder, value: Any?) {
    when (value) {
        null -> sb.append("null")
        is Boolean -> sb.append(value.toString())
        is Int, is Long, is Short, is Byte -> sb.append(value.toString())
        is Double -> sb.append(if (value.isFinite()) value.toString() else "null")
        is Float -> sb.append(if (value.isFinite()) value.toString() else "null")
        is Number -> sb.append(value.toString())
        is CharSequence, is Char -> encodeString(sb, value.toString())
        is Map<*, *> -> encodeObject(sb, value)
        is Iterable<*> -> encodeArray(sb, value)
        is BooleanArray -> encodeArray(sb, value.toList())
        is IntArray -> encodeArray(sb, value.toList())
        is LongArray -> encodeArray(sb, value.toList())
        is DoubleArray -> encodeArray(sb, value.toList())
        is Array<*> -> encodeArray(sb, value.asIterable())
        else -> encodeString(sb, value.toString())
    }
}

/**
 * The `Json` used for live-island state round-trips. `encodeDefaults = true` so a model at its default
 * (e.g. `count = 0`) still serializes its fields (the saved state is explicit); `ignoreUnknownKeys` for
 * forward-compatibility.
 */
@PublishedApi
internal val bmlStateJson: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * Encode a live-island `@Serializable` state model to JSON for `data-bml-state` / the session.
 * **Native-safe:** the reified [T] lets the serialization plugin resolve `T.serializer()` at the
 * (generated) call site — every call site has a concrete type, so no reflective `serializer<T>()`.
 */
inline fun <reified T> encodeState(model: T): String = bmlStateJson.encodeToString(model)

/**
 * Encodes browser-backed state for an `application/json` script element. Escaping `<` preserves the
 * same JSON value while preventing a model string containing `</script>` from ending the element.
 * The stable escaped representation is also returned by actions, so default-state comparison and
 * browser persistence use exactly the bytes emitted during server rendering.
 */
inline fun <reified T> encodeClientState(model: T): String = encodeState(model).replace("<", "\\u003c")

/** Inverse of [encodeState]; concrete reified [T] keeps it native-safe. */
inline fun <reified T> decodeState(text: String): T = bmlStateJson.decodeFromString(text)

/**
 * Signals that posted client-backed live state cannot be decoded by the current generated model.
 * The BML action boundary maps this specific failure to an invalid-state response so the browser can
 * discard only incompatible persisted state without treating ordinary action or render failures as corruption.
 */
class InvalidBmlClientStateException(cause: SerializationException) :
    RuntimeException("posted BML client state is incompatible with the current model", cause)

/**
 * Decodes client-backed live state while preserving a distinct incompatibility signal for the HTTP boundary.
 * Server-session state continues to use [decodeState], because its corruption is a server failure rather than
 * browser-owned persisted data that the client can safely discard.
 */
inline fun <reified T> decodeClientState(text: String): T =
    try {
        decodeState<T>(text)
    } catch (error: SerializationException) {
        throw InvalidBmlClientStateException(error)
    }

/**
 * A posted declarative/programmatic server-action request body. [page] (the route pattern of the page the client is
 * on) + [stateKey] together pick the dispatcher — state keys are unique only *within* a page, so two
 * pages may both define `counterModel`; [page] disambiguates. A component state's key carries its
 * per-instance suffix (`"<tag>.<provides>:<key>"`) and resolves by prefix instead. [state] is the posted
 * client model (empty for a server scope, whose model is loaded from the server session identified by
 * the `bml_session` cookie, not the body). [args] are the action's arguments — the evaluated non-`ctx`
 * parameters of the authored action expression, in declaration order. [query] and [locale] preserve the
 * current page's render context. [renderView] is false when a site-scoped component is being used headlessly,
 * allowing generated dispatchers to skip unused view work.
 */
data class IslandActionRequest(
    val page: String,
    val stateKey: String,
    val method: String,
    val state: String,
    val args: JsonArray = JsonArray(emptyList()),
    val renderView: Boolean = true,
    val path: String = "",
    val query: Map<String, String> = emptyMap(),
    val locale: String? = null,
)

/** Parse a `POST /_bml/action` body (hand-written, native-safe). */
fun parseActionRequest(text: String): IslandActionRequest {
    val obj = (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
        ?: return IslandActionRequest("", "", "", "")
    fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val query = when (val value = obj["query"]) {
        null -> emptyMap<String, String>()
        is JsonObject -> buildMap<String, String> {
            for ((key, entry) in value) {
                val string = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return IslandActionRequest("", "", "", "")
                put(key, string)
            }
        }
        else -> return IslandActionRequest("", "", "", "")
    }
    val locale = when (val value = obj["locale"]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content
            ?: return IslandActionRequest("", "", "", "")
        else -> return IslandActionRequest("", "", "", "")
    }
    return IslandActionRequest(
        page = str("page").orEmpty(),
        stateKey = str("stateKey").orEmpty(),
        method = str("method").orEmpty(),
        state = str("state").orEmpty(),
        query = query,
        locale = locale,
        args = obj["args"] as? JsonArray ?: JsonArray(emptyList()),
        renderView = (obj["renderView"] as? JsonPrimitive)?.booleanOrNull ?: true,
        path = str("path").orEmpty(),
    )
}

/**
 * Decode one positional action argument to the model method's parameter type — the reified [T] is
 * inferred from the call the compiler generates (`model.remove(actionArg(args, 0))`), so the author's
 * method signature is the source of truth. **Native-safe:** primitives only, decoded off the raw
 * [JsonPrimitive] content (which also tolerates form-field strings like `"42"` for a `Long` parameter).
 */
inline fun <reified T> actionArg(args: JsonArray, index: Int): T {
    val prim = args.getOrNull(index) as? JsonPrimitive
        ?: error("live action argument $index is missing or not a primitive")
    return when (T::class) {
        String::class -> prim.content as T
        Int::class -> prim.content.toInt() as T
        Long::class -> prim.content.toLong() as T
        Boolean::class -> prim.content.toBooleanStrict() as T
        Double::class -> prim.content.toDouble() as T
        else -> error("live action arguments must be String/Int/Long/Boolean/Double, not ${T::class.simpleName}")
    }
}

/**
 * The ` data-bml-args="…"` marker chunk for an action element, HTML-attribute-escaped so callers can
 * append it straight into raw open-tag markup. [values] are the action's render-time argument values;
 * a `form.<field>` argument is passed as `mapOf("__bmlField" to "<field>")` — the client runtime
 * replaces that sentinel with the form field's value at submit time.
 */
fun actionArgsAttr(values: List<Any?>): String {
    val sb = StringBuilder()
    encodeArray(sb, values)
    return " data-bml-args=\"" + Html.escapeAttribute(sb.toString()) + "\""
}

/**
 * An action element's full live-marker chunk (leading space, HTML-attribute-escaped, appendable
 * straight into raw open-tag markup): ` data-bml-state-key="…" data-bml-method="…"`, plus
 * ` data-bml-event="submit"` for a form action and ` data-bml-args="…"` when the action carries
 * arguments. Generated open tags call this so page-static and per-instance component keys emit
 * through one code path.
 */
fun actionMarkers(
    stateKey: String,
    method: String,
    event: String? = null,
    args: List<Any?>? = null,
    debounceMs: Long? = null,
    throttleMs: Long? = null,
    coalesce: Boolean = false,
    keepalive: Boolean = false,
    flushOnPageHide: Boolean = false,
): String {
    val sb = StringBuilder()
    sb.append(" data-bml-state-key=\"").append(Html.escapeAttribute(stateKey)).append('"')
    sb.append(" data-bml-method=\"").append(Html.escapeAttribute(method)).append('"')
    if (event != null && event != "click") sb.append(" data-bml-event=\"").append(Html.escapeAttribute(event)).append('"')
    if (!args.isNullOrEmpty()) sb.append(actionArgsAttr(args))
    debounceMs?.let { sb.append(" data-bml-debounce-ms=\"").append(it).append('"') }
    throttleMs?.let { sb.append(" data-bml-throttle-ms=\"").append(it).append('"') }
    if (coalesce) sb.append(" data-bml-coalesce")
    if (keepalive) sb.append(" data-bml-keepalive")
    if (flushOnPageHide) sb.append(" data-bml-flush-pagehide")
    return sb.toString()
}

/** Encode an [IslandActionResult] as `{"state": …|null, "html": …|null}` (hand-written, native-safe). */
fun encodeActionResponse(result: IslandActionResult): String {
    val sb = StringBuilder("{")
    encodeString(sb, "state"); sb.append(':')
    if (result.state == null) sb.append("null") else encodeString(sb, result.state)
    sb.append(',')
    encodeString(sb, "html"); sb.append(':')
    if (result.html == null) sb.append("null") else encodeString(sb, result.html)
    sb.append('}')
    return sb.toString()
}

private fun encodeString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        }
    }
    sb.append('"')
}
