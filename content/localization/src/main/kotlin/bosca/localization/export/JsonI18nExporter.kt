@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.export

import bosca.localization.model.ExportFormat
import bosca.localization.model.ExportResult
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationTranslation
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.ExperimentalUuidApi

/**
 * Nuxt `@nuxtjs/i18n` export format.
 *
 * Shape is the same nested tree as [JsonNestedExporter], but plurals are
 * encoded as pipe-separated strings in the order Vue I18n expects
 * (`zero | one | other`). Keys with `.` delimiters expand into a nested object
 * tree so that `$t('settings.profile.title')` lookups resolve.
 *
 * ICU placeholders pass through unchanged; Vue I18n interpolates
 * `{name}`-style placeholders natively.
 */
class JsonI18nExporter : LocalizationExporter {

    override val format: ExportFormat = ExportFormat.JSON_I18N

    private val pluralOrder = listOf("zero", "one", "two", "few", "many", "other")

    override suspend fun export(
        languageTag: String,
        strings: List<LocalizationString>,
        translations: Map<UUID, LocalizationTranslation>,
        pluralTranslations: Map<UUID, List<LocalizationPluralTranslation>>,
        sourceLanguage: String,
        sourceTranslations: Map<UUID, LocalizationTranslation>
    ): ExportResult {
        val root = linkedMapOf<String, Any>()
        for (string in strings) {
            val parts = string.key.split('.')
            if (string.plural) {
                val plurals = pluralTranslations[string.id] ?: continue
                val byCategory = plurals.associateBy { it.pluralCategory }
                val text = pluralOrder.mapNotNull { byCategory[it]?.text }.joinToString(" | ")
                insert(root, parts, JsonPrimitive(text))
            } else {
                val translation = translations[string.id] ?: continue
                insert(root, parts, JsonPrimitive(translation.text))
            }
        }
        return ExportResult(
            content = toJson(root).toString(),
            contentType = "application/json",
            fileName = "$languageTag.json"
        )
    }

    private fun insert(root: MutableMap<String, Any>, path: List<String>, value: JsonElement) {
        var node = root
        for (i in 0 until path.size - 1) {
            val segment = path[i]
            val existing = node[segment]
            if (existing is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                node = existing as MutableMap<String, Any>
            } else {
                require(existing == null) {
                    "Key collision: '${path.take(i + 1).joinToString(".")}' is already a leaf value but '${path.joinToString(".")}' requires it to be a nested object"
                }
                val fresh = linkedMapOf<String, Any>()
                node[segment] = fresh
                node = fresh
            }
        }
        val lastSegment = path.last()
        val existingLeaf = node[lastSegment]
        require(existingLeaf == null || existingLeaf is JsonElement) {
            "Key collision: '${path.joinToString(".")}' would overwrite nested keys under '${path.joinToString(".")}'"
        }
        node[lastSegment] = value
    }

    private fun toJson(node: Map<String, Any>): JsonObject = JsonObject(node.mapValues { (_, v) ->
        when (v) {
            is JsonElement -> v
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                toJson(v as Map<String, Any>)
            }
            else -> JsonPrimitive(v.toString())
        }
    })
}
