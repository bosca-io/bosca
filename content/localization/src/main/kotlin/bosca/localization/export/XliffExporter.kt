@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.export

import bosca.localization.model.ExportFormat
import bosca.localization.model.ExportResult
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationTranslation
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * XLIFF 1.2 exporter for interop with translation memory and CAT tools.
 *
 * Produces a single `<file>` element for [languageTag] with a `<body>` containing
 * one `<trans-unit>` per translation. Plural strings decompose into one trans-unit
 * per category, keyed as `{stringKey}.{category}`.
 *
 * The exporter emits only target-language segments; a fuller implementation
 * would also emit source-language `<source>` text and include approval/state
 * metadata. This format is included primarily so Crowdin and similar tools can
 * ingest Bosca exports without a custom adapter.
 */
class XliffExporter : LocalizationExporter {

    override val format: ExportFormat = ExportFormat.XLIFF

    override suspend fun export(
        languageTag: String,
        strings: List<LocalizationString>,
        translations: Map<UUID, LocalizationTranslation>,
        pluralTranslations: Map<UUID, List<LocalizationPluralTranslation>>,
        sourceLanguage: String,
        sourceTranslations: Map<UUID, LocalizationTranslation>
    ): ExportResult {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">""")
        sb.appendLine("""  <file source-language="${escape(sourceLanguage)}" target-language="${escape(languageTag)}" datatype="plaintext" original="bosca">""")
        sb.appendLine("    <body>")
        for (string in strings) {
            val sourceText = sourceTranslations[string.id]?.text ?: string.key
            if (string.plural) {
                val plurals = pluralTranslations[string.id].orEmpty()
                for (plural in plurals) {
                    sb.appendLine("""      <trans-unit id="${escape(string.key)}.${plural.pluralCategory}">""")
                    sb.appendLine("        <source>${escape(sourceText)}</source>")
                    sb.appendLine("        <target>${escape(plural.text)}</target>")
                    string.context?.let { sb.appendLine("        <note>${escape(it)}</note>") }
                    sb.appendLine("      </trans-unit>")
                }
            } else {
                val translation = translations[string.id] ?: continue
                sb.appendLine("""      <trans-unit id="${escape(string.key)}">""")
                sb.appendLine("        <source>${escape(sourceText)}</source>")
                sb.appendLine("        <target>${escape(translation.text)}</target>")
                string.context?.let { sb.appendLine("        <note>${escape(it)}</note>") }
                sb.appendLine("      </trans-unit>")
            }
        }
        sb.appendLine("    </body>")
        sb.appendLine("  </file>")
        sb.appendLine("</xliff>")
        return ExportResult(
            content = sb.toString(),
            contentType = "application/xliff+xml",
            fileName = "$languageTag.xliff"
        )
    }

    private fun escape(source: String): String = source
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
