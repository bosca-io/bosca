package bosca.bml.codegen

/**
 * One localized message the compiler harvested from `t`/`t:` markup — the
 * unit of the i18n manifest the CLI uploads (`bosca bml i18n push`). The authored text is the
 * source-language message; placeholders record which expressions feed which `{name}`s.
 */
data class BmlI18nEntry(
    val key: String,
    /** Non-plural source message; null for plural entries. */
    val message: String?,
    /** Plural source forms by CLDR category name (`ONE`, `OTHER`, …); empty for plain entries. */
    val pluralForms: Map<String, String> = emptyMap(),
    val placeholders: List<BmlI18nPlaceholder> = emptyList(),
    /** Where the string is authored: an element body or a `t:<attr>` attribute pairing. */
    val origin: BmlI18nOrigin,
    val file: String,
    val line: Int,
) {
    val plural: Boolean get() = pluralForms.isNotEmpty()
}

data class BmlI18nPlaceholder(val name: String, val expression: String)

/** ELEMENT/ATTRIBUTE carry authored source text; FUNCTION is a static `t("key", …)` call. */
enum class BmlI18nOrigin { ELEMENT, ATTRIBUTE, FUNCTION }

/** Where the module's i18n manifest lands inside the resources output (and the packaged jar). */
const val I18N_MANIFEST_PATH: String = "bml/i18n-manifest.json"
const val I18N_MANIFEST_VERSION: Int = 1

// A static `t("key")` call in embedded code (server scripts, interpolations, client TS) — the
// literal first argument is a real message key the CLI should create. `\b` keeps `format(` and
// friends out; dynamic keys simply don't match and stay un-harvested (legal, just not pushable).
// A second string literal is the authored-default overload's source message — harvested so push
// seeds it — UNLESS it is followed by `to` (then it's a placeholder-pair name, not a message).
private val T_CALL = Regex("""\bt\(\s*"((?:[^"\\]|\\.)*)"(?:\s*,\s*"((?:[^"\\]|\\.)*)"(?!\s*to\b))?""")
private val T_PLACEHOLDER_ARGUMENT = Regex("""\s*"((?:[^"\\]|\\.)*)"\s+to\s+([\s\S]+?)\s*""")

/** Scans one `.bml` source for static `t("key")` calls, preserving each declaration for validation. */
internal fun scanFunctionKeys(source: String, file: String): List<BmlI18nEntry> =
    T_CALL.findAll(source)
        .map { match ->
            BmlI18nEntry(
                key = match.groupValues[1],
                message = match.groupValues[2].takeIf { it.isNotEmpty() },
                placeholders = remainingCallArguments(source, match.range.last + 1).mapNotNull { argument ->
                    T_PLACEHOLDER_ARGUMENT.matchEntire(argument)?.let {
                        BmlI18nPlaceholder(name = it.groupValues[1], expression = it.groupValues[2])
                    }
                },
                origin = BmlI18nOrigin.FUNCTION,
                file = file,
                line = source.substring(0, match.range.first).count { it == '\n' } + 1,
            )
        }
        .toList()

/**
 * Splits the arguments that follow the part of a static `t()` call matched by [T_CALL]. The
 * scanner is deliberately small, but it respects quoted strings and nested Kotlin delimiters so
 * placeholder expressions such as `format(user.name, locale)` stay one argument. An unmatched
 * closing parenthesis terminates the surrounding `t()` call.
 */
private fun remainingCallArguments(source: String, start: Int): List<String> {
    val arguments = mutableListOf<String>()
    val current = StringBuilder()
    var parentheses = 0
    var brackets = 0
    var braces = 0
    var quote: Char? = null
    var escaped = false

    for (index in start until source.length) {
        val character = source[index]
        if (quote != null) {
            current.append(character)
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == quote -> quote = null
            }
            continue
        }

        when (character) {
            '"', '\'' -> {
                quote = character
                current.append(character)
            }
            '(' -> {
                parentheses++
                current.append(character)
            }
            ')' -> {
                if (parentheses == 0 && brackets == 0 && braces == 0) {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let(arguments::add)
                    return arguments
                }
                parentheses--
                current.append(character)
            }
            '[' -> {
                brackets++
                current.append(character)
            }
            ']' -> {
                brackets--
                current.append(character)
            }
            '{' -> {
                braces++
                current.append(character)
            }
            '}' -> {
                braces--
                current.append(character)
            }
            ',' -> {
                if (parentheses == 0 && brackets == 0 && braces == 0) {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let(arguments::add)
                    current.clear()
                } else {
                    current.append(character)
                }
            }
            else -> current.append(character)
        }
    }
    return emptyList()
}

/** The i18n manifest JSON — the build-time contract `bosca bml i18n push` consumes. */
internal fun buildI18nManifest(entries: List<BmlI18nEntry>): String = buildString {
    appendLine("{")
    appendLine("  \"manifestVersion\": $I18N_MANIFEST_VERSION,")
    appendLine("  \"strings\": [")
    entries.forEachIndexed { i, e ->
        append("    {\"key\": ${jsonStr(e.key)}, \"plural\": ${e.plural}, \"origin\": ${jsonStr(e.origin.name)}")
        append(", \"file\": ${jsonStr(e.file)}, \"line\": ${e.line}")
        e.message?.let { append(", \"message\": ${jsonStr(it)}") }
        if (e.pluralForms.isNotEmpty()) {
            append(e.pluralForms.entries.joinToString(", ", ", \"forms\": {", "}") { "${jsonStr(it.key)}: ${jsonStr(it.value)}" })
        }
        if (e.placeholders.isNotEmpty()) {
            append(
                e.placeholders.joinToString(", ", ", \"placeholders\": [", "]") {
                    "{\"name\": ${jsonStr(it.name)}, \"expression\": ${jsonStr(it.expression)}}"
                },
            )
        }
        appendLine("}${if (i < entries.size - 1) "," else ""}")
    }
    appendLine("  ]")
    append("}")
}

private fun jsonStr(s: String): String = buildString {
    append('"')
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

/** The CLDR plural categories, lowercase — the legal `<t:…>` child tag names. */
internal val CLDR_CATEGORIES = listOf("zero", "one", "two", "few", "many", "other")

/** Whitespace runs collapse in folded templates — translators see prose, not markup indentation. */
internal val WHITESPACE_RUN = Regex("""\s+""")

/** A "simple" interpolation — a dotted (or safe-call) path — takes its trailing identifier as the placeholder name. */
internal val SIMPLE_PATH = Regex("""[A-Za-z_][A-Za-z0-9_]*(\??\.[A-Za-z_][A-Za-z0-9_]*)*""")

/**
 * Decodes the HTML entities authors legitimately write in markup text (`&amp;`, `&#8212;`, …)
 * into plain characters. Localized templates render through `w.text` (translations are data,
 * not markup), so authored entities must become characters at COMPILE time or they would
 * double-escape (`&amp;` -> `&amp;amp;`). Unknown entities pass through untouched.
 */
internal fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
            else -> NAMED_ENTITIES[body] ?: match.value
        }
    }
}

private val ENTITY = Regex("""&(#[xX]?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);""")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "mdash" to "—",
    "ndash" to "–",
    "hellip" to "…",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "laquo" to "«",
    "raquo" to "»",
    "lsquo" to "‘",
    "rsquo" to "’",
    "ldquo" to "“",
    "rdquo" to "”",
)
