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
 * Nested JSON where string keys with `.` delimiters expand into a nested tree.
 *
 * For example, `settings.profile.title` becomes
 * `{"settings":{"profile":{"title":"..."}}}` so consumers that expect path-based
 * lookup (Rails-style i18n gems, some React Intl projects) can find keys
 * ergonomically.
 *
 * Plural strings nest under a `plurals` object whose keys are CLDR categories.
 */
class JsonNestedExporter : LocalizationExporter {

    override val format: ExportFormat = ExportFormat.JSON_NESTED

    override suspend fun export(
        languageTag: String,
        strings: List<LocalizationString>,
        translations: Map<UUID, LocalizationTranslation>,
        pluralTranslations: Map<UUID, List<LocalizationPluralTranslation>>,
        sourceLanguage: String,
        sourceTranslations: Map<UUID, LocalizationTranslation>
    ): ExportResult {
        val root = NestedNode()
        for (string in strings) {
            val parts = string.key.split('.')
            if (string.plural) {
                val plurals = pluralTranslations[string.id] ?: continue
                val pluralObj = JsonObject(plurals.associate { it.pluralCategory to JsonPrimitive(it.text) })
                root.insert(parts + "plurals", pluralObj)
            } else {
                val translation = translations[string.id] ?: continue
                root.insert(parts, JsonPrimitive(translation.text))
            }
        }
        return ExportResult(
            content = root.toJson().toString(),
            contentType = "application/json",
            fileName = "$languageTag.json"
        )
    }

    private class NestedNode {
        private val children = linkedMapOf<String, Any>()

        fun insert(path: List<String>, value: JsonElement) {
            var node = this
            for (i in 0 until path.size - 1) {
                val segment = path[i]
                val existing = node.children[segment]
                val child = if (existing is NestedNode) existing else NestedNode().also { node.children[segment] = it }
                node = child
            }
            node.children[path.last()] = value
        }

        fun toJson(): JsonObject = JsonObject(children.mapValues { (_, v) ->
            when (v) {
                is NestedNode -> v.toJson()
                is JsonElement -> v
                else -> JsonPrimitive(v.toString())
            }
        })
    }
}
