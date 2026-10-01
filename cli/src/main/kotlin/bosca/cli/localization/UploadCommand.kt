package bosca.cli.localization

import bosca.graphql.client.execute
import bosca.graphql.gen.AddLocalizationString
import bosca.graphql.gen.GetLocalizationStringByKey
import bosca.graphql.gen.LocalizationStringInput
import bosca.graphql.gen.LocalizationTranslationInput
import bosca.graphql.gen.SetLocalizationTranslation
import bosca.graphql.gen.TranslationOrigin
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a source-language strings file and upserts each key into the project
 * via the GraphQL mutations exposed by the localization subsystem.
 *
 * Supported input formats for the initial release: flat JSON (`{"key": "value"}`)
 * and nested JSON (flattened on import using dot notation).
 */
class UploadCommand : LocalizationSubcommand(
    name = "upload",
    help = "Upload source-language strings from a file into a localization project"
) {

    private val format by option("--format", help = "Input file format: json-flat | json-nested")
        .default("json-flat")

    private val file by option("--file", help = "Path to source strings file").file(mustExist = true).required()

    private val languageTag by option("--language", help = "Source language tag").default("en")

    override fun run() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.parseToJsonElement(file.readText()).jsonObject
        val entries = when (format) {
            "json-flat" -> parsed.mapValues { it.value.jsonPrimitive.content }
            "json-nested" -> flatten(parsed)
            else -> throw IllegalArgumentException("Unsupported format: $format")
        }
        val gql = client()
        var added = 0
        var updated = 0
        for ((key, text) in entries) {
            val existing = gql.execute(
                GetLocalizationStringByKey,
                GetLocalizationStringByKey.Variables(project, key),
            ).localization.stringByKey
            val stringId = if (existing == null) {
                val created = gql.execute(
                    AddLocalizationString,
                    AddLocalizationString.Variables(
                        LocalizationStringInput(
                            key = key,
                            projectId = project,
                        ),
                    ),
                ).localization.addString
                added++
                created.id
            } else {
                updated++
                existing.id
            }
            gql.execute(
                SetLocalizationTranslation,
                SetLocalizationTranslation.Variables(
                    LocalizationTranslationInput(
                        languageTag = languageTag,
                        origin = TranslationOrigin.IMPORT,
                        originDetail = "bosca-localization upload",
                        stringId = stringId,
                        text = text,
                    ),
                ),
            )
        }
        echo("Upload complete: $added added, $updated updated.")
    }

    private fun flatten(
        source: JsonObject,
        prefix: String = "",
        out: MutableMap<String, String> = linkedMapOf()
    ): Map<String, String> {
        for ((key, value) in source) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when (value) {
                is JsonObject -> flatten(value, path, out)
                else -> out[path] = value.jsonPrimitive.content
            }
        }
        return out
    }
}
