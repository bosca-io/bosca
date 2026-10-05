package bosca.bml.codegen

import bosca.bml.parser.AttrText
import bosca.bml.parser.BoundAttribute
import bosca.bml.parser.ElementNode
import bosca.bml.parser.StaticAttribute

/** The compile-time form of a static attribute value for one declared prop type. */
internal sealed interface StaticPropValue {
    /**
     * Kotlin source that produces the value. [needsTypeCheck] is true for author expressions whose
     * type the compiler did not construct; literals and text concatenation are already the declared type.
     */
    data class Code(val expression: String, val needsTypeCheck: Boolean = false) : StaticPropValue

    /** The text cannot represent the declared type, for example `count="abc"` on an `Int` prop. */
    data class Invalid(val reason: String) : StaticPropValue

    /** The declared type has no literal text form (application classes, collections, arrays). */
    data object Unsupported : StaticPropValue
}

/**
 * Converts static attribute text to the prop's declared Kotlin type while generating code, so a
 * typed prop receives a typed value (`count="3"` → `3`) with no runtime parsing, and malformed
 * literals fail the build instead of the render.
 */
internal object StaticPropCoercion {
    private val TEXT_TYPES = setOf("String", "CharSequence")

    /** The declared type without nullability or a `kotlin.`/`java.lang.` qualifier. */
    fun rawType(declaredType: String): String {
        val type = declaredType.trim().removeSuffix("?").trimEnd()
        return when (type) {
            "java.lang.String" -> "String"
            "java.lang.CharSequence" -> "CharSequence"
            "java.lang.Number" -> "Number"
            else -> type.removePrefix("kotlin.")
        }
    }

    /**
     * True for the types BML understands well enough to judge static values itself: text types,
     * `Any`, and the scalar types [coerceText] converts. Anything else (application classes,
     * aliases, interfaces) is left to the Kotlin compiler.
     */
    fun isRecognizedType(declaredType: String): Boolean =
        rawType(declaredType).let { it in TEXT_TYPES || it == "Any" || it in SCALAR_TYPES }

    private val SCALAR_TYPES = setOf("Boolean", "Int", "Long", "Short", "Byte", "Double", "Float", "Char", "Number")

    /** True when static text already is the declared type's value. */
    fun acceptsText(declaredType: String): Boolean = rawType(declaredType).let { it in TEXT_TYPES || it == "Any" }

    /** True when a bare attribute's `true` is a value of the declared type. */
    fun acceptsBareAttribute(declaredType: String): Boolean = rawType(declaredType).let { it == "Boolean" || it == "Any" }

    /** Coerces literal [text] to [declaredType]. Numbers and booleans ignore surrounding whitespace. */
    fun coerceText(text: String, declaredType: String): StaticPropValue {
        val type = rawType(declaredType)
        if (type in TEXT_TYPES || type == "Any") return StaticPropValue.Code(kotlinStringLiteral(text))
        val trimmed = text.trim()
        fun invalid(kind: String) = StaticPropValue.Invalid("static value '$text' is not a valid $kind literal")
        return when (type) {
            "Boolean" -> when (trimmed) {
                "true", "false" -> StaticPropValue.Code(trimmed)
                else -> invalid("Boolean")
            }
            "Int" -> trimmed.toIntOrNull()?.let { StaticPropValue.Code(intLiteral(it)) } ?: invalid("Int")
            "Long" -> trimmed.toLongOrNull()?.let { StaticPropValue.Code(longLiteral(it)) } ?: invalid("Long")
            "Short" -> trimmed.toShortOrNull()?.let { StaticPropValue.Code("($it).toShort()") } ?: invalid("Short")
            "Byte" -> trimmed.toByteOrNull()?.let { StaticPropValue.Code("($it).toByte()") } ?: invalid("Byte")
            "Double" -> trimmed.toDoubleOrNull()?.takeIf(Double::isFinite)
                ?.let { StaticPropValue.Code(it.toString()) } ?: invalid("finite Double")
            "Float" -> trimmed.toFloatOrNull()?.takeIf(Float::isFinite)
                ?.let { StaticPropValue.Code("${it}f") } ?: invalid("finite Float")
            "Number" -> trimmed.toIntOrNull()?.let { StaticPropValue.Code(intLiteral(it)) }
                ?: trimmed.toLongOrNull()?.let { StaticPropValue.Code(longLiteral(it)) }
                ?: trimmed.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { StaticPropValue.Code(it.toString()) }
                ?: invalid("finite number")
            "Char" -> text.singleOrNull()?.let { StaticPropValue.Code(charLiteral(it)) } ?: invalid("single-character Char")
            else -> StaticPropValue.Unsupported
        }
    }

    /**
     * Kotlin source for a static `default="…"`. Text props take the text literally. For numeric and
     * Boolean props a valid literal is converted (`default="1.5"` on a `Float` is `1.5f`); anything
     * else stays Kotlin source, as it always has, so `default="Variant.Primary"` and
     * `default="isAdmin"` keep working. `Char` defaults stay source because `default="c"` could name
     * a value as easily as a character.
     */
    fun staticDefault(text: String, declaredType: String): String {
        val type = rawType(declaredType)
        if (type in TEXT_TYPES) return kotlinStringLiteral(text)
        if (type !in LITERAL_DEFAULT_TYPES) return text
        return (coerceText(text, declaredType) as? StaticPropValue.Code)?.expression ?: text
    }

    /**
     * True when every type name in [declaredType] resolves in any generated file without imports:
     * Kotlin's default-imported types, or names qualified from a lowercase package segment
     * (`bosca.profiles.web.Row`). A short or nested name such as `Row` or `Feed.Row` depends on
     * the declaring file's imports.
     */
    fun resolvesWithoutImports(declaredType: String): Boolean =
        TYPE_NAME.findAll(declaredType).map { it.value }
            .filterNot { it in VARIANCE_KEYWORDS }
            .all { name ->
                if ('.' in name) name.first().isLowerCase() else name in DEFAULT_IMPORTED_TYPES
            }

    private val LITERAL_DEFAULT_TYPES = setOf("Boolean", "Int", "Long", "Short", "Byte", "Double", "Float", "Number")
    private val TYPE_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
    private val VARIANCE_KEYWORDS = setOf("in", "out")
    private val DEFAULT_IMPORTED_TYPES = setOf(
        "Any", "Nothing", "Unit", "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double", "Char",
        "String", "CharSequence", "Number", "Comparable", "Array", "BooleanArray", "ByteArray",
        "ShortArray", "IntArray", "LongArray", "FloatArray", "DoubleArray", "CharArray", "Pair", "Triple",
        "Iterable", "Collection", "List", "Set", "Map", "MutableIterable", "MutableCollection",
        "MutableList", "MutableSet", "MutableMap", "Sequence",
    )

    /** Each declared component tag's props mapped to the type a caller may pass; see [acceptedPropTypes]. */
    fun declaredPropTypes(declarations: Map<String, ElementNode>): Map<String, Map<String, String>> =
        declarations.mapValues { (_, declaration) -> acceptedPropTypes(declaration) }

    /**
     * The type a caller may pass for each `<prop>`. An untyped prop is `Any?`. A prop with a default
     * accepts null as well, because the component reads it as `props[name] as? T ?: default`, so
     * passing a nullable value deliberately means "use the default when absent".
     */
    fun acceptedPropTypes(declaration: ElementNode): Map<String, String> =
        declaration.children.filterIsInstance<ElementNode>()
            .filter { it.namespace == null && it.name == "prop" }
            .mapNotNull { prop ->
                val name = literal(prop, "name") ?: return@mapNotNull null
                val type = literal(prop, "type") ?: "Any?"
                // Same rule as the generator's prop parsing: `:default="…"` or a literal `default="…"`.
                val hasDefault = prop.attributes.any { it is BoundAttribute && it.name == "default" } ||
                    literal(prop, "default") != null
                name to if (hasDefault) nullable(type) else type
            }
            .toMap()

    /**
     * [type] made nullable. A function type needs parentheses: `(A) -> B?` is a function returning a
     * nullable B, so it is only already nullable when written `((A) -> B)?`.
     */
    private fun nullable(type: String): String {
        val trimmed = type.trim()
        if ("->" !in trimmed) return if (trimmed.endsWith("?")) trimmed else "$trimmed?"
        return if (isParenthesizedNullable(trimmed)) trimmed else "($trimmed)?"
    }

    /** True for `( … )?` where the opening parenthesis closes just before the `?`. */
    private fun isParenthesizedNullable(type: String): Boolean {
        if (!type.startsWith("(") || !type.endsWith(")?")) return false
        var depth = 0
        for (index in 0 until type.length - 1) {
            when (type[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index == type.length - 2
                }
            }
        }
        return false
    }

    private fun literal(element: ElementNode, name: String): String? {
        val value = element.attributes.filterIsInstance<StaticAttribute>().firstOrNull { it.name == name }?.value
            ?: return null
        if (!value.all { it is AttrText }) return null
        return value.joinToString("") { (it as AttrText).value }
    }

    // `-2147483648` is not an Int literal in Kotlin: the digits alone overflow before negation.
    private fun intLiteral(value: Int): String = if (value == Int.MIN_VALUE) "Int.MIN_VALUE" else value.toString()

    private fun longLiteral(value: Long): String = if (value == Long.MIN_VALUE) "Long.MIN_VALUE" else "${value}L"

    private fun charLiteral(value: Char): String = "'\\u%04x'".format(value.code)
}

/** A Kotlin string literal for [value], escaping the characters Kotlin interprets inside quotes. */
internal fun kotlinStringLiteral(value: String): String {
    val out = StringBuilder("\"")
    for (c in value) {
        when (c) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '$' -> out.append("\\$")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> out.append(c)
        }
    }
    out.append('"')
    return out.toString()
}
