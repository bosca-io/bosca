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
 * iOS `.stringsdict` plist exporter for plural forms.
 *
 * Every plural [LocalizationString] becomes a top-level key whose value is a
 * `NSStringLocalizedFormatKey`-based dict with one entry per CLDR plural category
 * (`NSStringFormatSpecTypeKey = NSStringPluralRuleType`, spec = `%d` by default).
 *
 * Non-plural strings are skipped; they belong in [IosStringsExporter] output.
 * ICU placeholders are rewritten for iOS at export time.
 */
class IosStringsdictExporter : LocalizationExporter {

    override val format: ExportFormat = ExportFormat.IOS_STRINGSDICT

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
        sb.appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        sb.appendLine("""<plist version="1.0">""")
        sb.appendLine("<dict>")
        for (string in strings) {
            if (!string.plural) continue
            val plurals = pluralTranslations[string.id].orEmpty()
            if (plurals.isEmpty()) continue
            sb.appendLine("    <key>${escape(string.key)}</key>")
            sb.appendLine("    <dict>")
            sb.appendLine("        <key>NSStringLocalizedFormatKey</key>")
            sb.appendLine("        <string>%#@quantity@</string>")
            sb.appendLine("        <key>quantity</key>")
            sb.appendLine("        <dict>")
            sb.appendLine("            <key>NSStringFormatSpecTypeKey</key>")
            sb.appendLine("            <string>NSStringPluralRuleType</string>")
            sb.appendLine("            <key>NSStringFormatValueTypeKey</key>")
            sb.appendLine("            <string>d</string>")
            for (plural in plurals) {
                sb.appendLine("            <key>${plural.pluralCategory}</key>")
                sb.appendLine("            <string>${escape(PlaceholderConverter.toIos(plural.text))}</string>")
            }
            sb.appendLine("        </dict>")
            sb.appendLine("    </dict>")
        }
        sb.appendLine("</dict>")
        sb.appendLine("</plist>")
        return ExportResult(
            content = sb.toString(),
            contentType = "application/xml",
            fileName = "$languageTag.lproj/Localizable.stringsdict"
        )
    }

    private fun escape(source: String): String = source
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
