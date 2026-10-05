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
import kotlin.uuid.ExperimentalUuidApi

/**
 * Flat `{"key": "value"}` JSON export. Plural strings are flattened into
 * `{key}_{category}` entries (e.g. `items_count_one`, `items_count_other`)
 * because the flat format has no native representation for plural rules.
 *
 * ICU placeholders pass through verbatim since the flat JSON format is used by
 * consumers that already understand ICU.
 */
class JsonFlatExporter : LocalizationExporter {

    override val format: ExportFormat = ExportFormat.JSON_FLAT

    override suspend fun export(
        languageTag: String,
        strings: List<LocalizationString>,
        translations: Map<UUID, LocalizationTranslation>,
        pluralTranslations: Map<UUID, List<LocalizationPluralTranslation>>,
        sourceLanguage: String,
        sourceTranslations: Map<UUID, LocalizationTranslation>
    ): ExportResult {
        val entries = linkedMapOf<String, JsonPrimitive>()
        for (string in strings) {
            if (string.plural) {
                val plurals = pluralTranslations[string.id] ?: continue
                for (plural in plurals) {
                    entries["${string.key}_${plural.pluralCategory}"] = JsonPrimitive(plural.text)
                }
            } else {
                val translation = translations[string.id] ?: continue
                entries[string.key] = JsonPrimitive(translation.text)
            }
        }
        val body = JsonObject(entries).toString()
        return ExportResult(
            content = body,
            contentType = "application/json",
            fileName = "$languageTag.json"
        )
    }
}
