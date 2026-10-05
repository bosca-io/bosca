@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.export

import bosca.localization.model.ExportFormat
import bosca.localization.model.ExportResult
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationTranslation
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.uuid.ExperimentalUuidApi

/**
 * Flutter Application Resource Bundle (ARB) exporter.
 *
 * ARB stores translations as a flat JSON object where each key is paired with a
 * metadata entry under `@key` describing its placeholders and description.
 * Plural strings use ICU plural syntax: `{count, plural, one {...} other {...}}`.
 * ICU placeholders pass through unchanged because ARB uses ICU natively.
 */
class ArbExporter : LocalizationExporter {

    override val format: ExportFormat = ExportFormat.ARB

    override suspend fun export(
        languageTag: String,
        strings: List<LocalizationString>,
        translations: Map<UUID, LocalizationTranslation>,
        pluralTranslations: Map<UUID, List<LocalizationPluralTranslation>>,
        sourceLanguage: String,
        sourceTranslations: Map<UUID, LocalizationTranslation>
    ): ExportResult {
        val entries = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        entries["@@locale"] = JsonPrimitive(languageTag)
        for (string in strings) {
            if (string.plural) {
                val plurals = pluralTranslations[string.id].orEmpty()
                if (plurals.isEmpty()) continue
                val icuBody = buildString {
                    append("{count, plural,")
                    for (plural in plurals) {
                        append(" ${plural.pluralCategory} {${plural.text}}")
                    }
                    append("}")
                }
                entries[string.key] = JsonPrimitive(icuBody)
                entries["@${string.key}"] = metadataFor(string)
            } else {
                val translation = translations[string.id] ?: continue
                entries[string.key] = JsonPrimitive(translation.text)
                entries["@${string.key}"] = metadataFor(string)
            }
        }
        return ExportResult(
            content = JsonObject(entries).toString(),
            contentType = "application/json",
            fileName = "intl_$languageTag.arb"
        )
    }

    private fun metadataFor(string: LocalizationString): JsonObject = buildJsonObject {
        string.context?.let { put("description", JsonPrimitive(it)) }
    }
}
