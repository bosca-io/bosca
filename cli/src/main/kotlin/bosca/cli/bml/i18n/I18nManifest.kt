package bosca.cli.bml.i18n

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The compiler-emitted i18n manifest: every message key authored in `t`/`t:` markup
 * (with its source text) plus every static `t("key")` call. Written by the BML K2 plugin during
 * compilation to `bml/i18n-manifest.json` under the resources output — the CLI never re-parses
 * `.bml` itself, so what pushes is exactly what compiled.
 */
data class I18nManifestString(
    val key: String,
    val plural: Boolean,
    /** ELEMENT / ATTRIBUTE carry authored source text; FUNCTION is a bare `t("key")` call. */
    val origin: String,
    val file: String,
    val line: Int,
    /** Non-plural source message; null for plural entries and FUNCTION keys. */
    val message: String?,
    /** Plural source forms by CLDR category (`ONE`, `OTHER`, …); empty for plain entries. */
    val forms: Map<String, String>,
    /** Declared placeholders: name -> the Kotlin expression that fills it. */
    val placeholders: List<I18nManifestPlaceholder>,
)

data class I18nManifestPlaceholder(val name: String, val expression: String)

object I18nManifest {

    /** Where the compile leaves the manifest, relative to a BML project directory. */
    const val DEFAULT_PATH = "build/generated/bml/resources/bml/i18n-manifest.json"

    fun parse(file: File): List<I18nManifestString> {
        require(file.isFile) {
            "No i18n manifest at ${file.absolutePath} — build the project first (./gradlew build); " +
                "the BML compiler writes it during compilation."
        }
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val version = root["manifestVersion"]?.jsonPrimitive?.intOrNull
        require(version == 1) { "Unsupported i18n manifest version $version (expected 1) in ${file.absolutePath}" }
        return root["strings"]?.jsonArray.orEmpty().map { element ->
            val obj = element.jsonObject
            I18nManifestString(
                key = obj.getValue("key").jsonPrimitive.content,
                plural = obj["plural"]?.jsonPrimitive?.content == "true",
                origin = obj["origin"]?.jsonPrimitive?.content ?: "ELEMENT",
                file = obj["file"]?.jsonPrimitive?.content ?: "?",
                line = obj["line"]?.jsonPrimitive?.intOrNull ?: 0,
                message = obj["message"]?.jsonPrimitive?.content,
                forms = obj["forms"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                placeholders = obj["placeholders"]?.jsonArray.orEmpty().map { p ->
                    I18nManifestPlaceholder(
                        name = p.jsonObject.getValue("name").jsonPrimitive.content,
                        expression = p.jsonObject["expression"]?.jsonPrimitive?.content.orEmpty(),
                    )
                },
            )
        }
    }
}
