@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.export

import bosca.localization.model.ExportFormat
import bosca.localization.model.ExportResult
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationTranslation
import bosca.localization.placeholder.PlaceholderConverter
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Android / Compose Multiplatform `strings.xml` exporter.
 *
 * Produces a `<resources>` root with `<string>` entries for plain translations
 * and `<plurals>` with `<item>` children for plural strings. ICU placeholders
 * are rewritten to Android positional specifiers via [PlaceholderConverter.toAndroid].
 *
 * The file path is `values-{tag}/strings.xml` (or `values/strings.xml` when
 * [languageTag] equals the project's source language). Callers writing to disk
 * should use the [ExportResult.fileName] verbatim.
 */
class AndroidXmlExporter : LocalizationExporter {

    override val format: ExportFormat = ExportFormat.ANDROID_XML

    override suspend fun export(
        languageTag: String,
        strings: List<LocalizationString>,
        translations: Map<UUID, LocalizationTranslation>,
        pluralTranslations: Map<UUID, List<LocalizationPluralTranslation>>,
        sourceLanguage: String,
        sourceTranslations: Map<UUID, LocalizationTranslation>
    ): ExportResult {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("<resources>")
        for (string in strings) {
            if (string.plural) {
                val plurals = pluralTranslations[string.id].orEmpty()
                if (plurals.isEmpty()) continue
                sb.appendLine("""    <plurals name="${escapeAttribute(string.key)}">""")
                for (plural in plurals) {
                    val converted = PlaceholderConverter.toAndroid(plural.text)
                    sb.appendLine("""        <item quantity="${plural.pluralCategory}">${escapeText(converted)}</item>""")
                }
                sb.appendLine("    </plurals>")
            } else {
                val translation = translations[string.id] ?: continue
                val converted = PlaceholderConverter.toAndroid(translation.text)
                sb.appendLine("""    <string name="${escapeAttribute(string.key)}">${escapeText(converted)}</string>""")
            }
        }
        sb.appendLine("</resources>")
        val dir = if (languageTag == sourceLanguage) "values" else "values-$languageTag"
        return ExportResult(
            content = sb.toString(),
            contentType = "application/xml",
            fileName = "$dir/strings.xml"
        )
    }

    /** Escapes XML attribute values (ampersand, angle brackets, double quotes). */
    private fun escapeAttribute(source: String): String = source
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Escapes Android XML element text (ampersand, angle brackets, apostrophes). */
    private fun escapeText(source: String): String = source
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
}
