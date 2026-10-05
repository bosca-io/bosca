package bosca.cli.bml.i18n

import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.execute
import bosca.graphql.gen.AddLocalizationString
import bosca.graphql.gen.DeleteLocalizationPluralTranslations
import bosca.graphql.gen.GetLocalizationProjectConfig
import bosca.graphql.gen.GetLocalizationProjectStrings
import bosca.graphql.gen.LocalizationPlaceholderInput
import bosca.graphql.gen.LocalizationPluralTranslationInput
import bosca.graphql.gen.LocalizationStringInput
import bosca.graphql.gen.LocalizationTranslationInput
import bosca.graphql.gen.PluralCategory
import bosca.graphql.gen.SetLocalizationPluralTranslation
import bosca.graphql.gen.SetLocalizationTranslation
import bosca.graphql.gen.TranslationOrigin
import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

/**
 * Feeds the manifest's keys into the localization project, create-and-seed
 * only:
 *  - missing keys are CREATED (context = file:line + origin, declared placeholders, plural flag)
 *    and their authored text seeds the SOURCE-language translation (plural category rows included);
 *  - keys whose authored source text changed get their source-language translation updated and
 *    are REPORTED so their translations can be re-reviewed — other languages are never touched;
 *  - catalog keys are never deleted; absent keys are reported as orphans. Changed plural entries
 *    replace their source-language category rows so removed categories cannot survive a push.
 */
class I18nPushCommand : I18nRemoteCommand(
    name = "push",
    helpText = "Create the manifest's keys in the localization project and seed source-language text",
) {

    private class ExistingString(
        val id: Uuid,
        val plural: Boolean,
        val sourceText: String?,
        val sourceForms: Map<String, String>,
    )

    override fun run() = runBlocking {
        val entries = loadManifest()
        if (entries.isEmpty()) {
            echo("No localization keys in ${manifestFile.path}; nothing to push.")
            return@runBlocking
        }
        val gql = client()
        val projectId = project

        val config = gql.execute(
            GetLocalizationProjectConfig,
            GetLocalizationProjectConfig.Variables(projectId),
        ).localization.project
            ?: throw CliktError("Localization project $projectId not found (or not readable)")
        val sourceLanguage = config.sourceLanguage

        val existing = fetchExisting(gql, projectId, sourceLanguage)

        var created = 0
        var seeded = 0
        var unchanged = 0
        val updatedKeys = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        for (entry in entries) {
            val current = existing[entry.key]
            if (current == null) {
                val stringId = addString(gql, projectId, entry)
                created++
                seeded += seedSource(gql, stringId, sourceLanguage, entry)
                continue
            }
            if (entry.message == null && entry.forms.isEmpty()) {
                unchanged++ // a bare t("key") call: nothing authored to compare or seed
                continue
            }
            if (current.plural != entry.plural) {
                warnings += "${entry.key}: manifest says plural=${entry.plural} but the project has " +
                    "plural=${current.plural} — fix the string in Studio; its text was left untouched"
                continue
            }
            val changed = if (entry.plural) entry.forms != current.sourceForms else entry.message != current.sourceText
            if (!changed) {
                unchanged++
                continue
            }
            if (entry.plural) deleteSourceForms(gql, current.id, sourceLanguage)
            seedSource(gql, current.id, sourceLanguage, entry)
            updatedKeys += entry.key
        }

        val orphans = (existing.keys - entries.map { it.key }.toSet()).sorted()

        echo("Push complete against project $projectId (source language $sourceLanguage):")
        echo("  created $created string(s), seeded $seeded source translation(s), $unchanged unchanged.")
        if (updatedKeys.isNotEmpty()) {
            echo("  source text UPDATED for ${updatedKeys.size} key(s) — re-review their translations:")
            updatedKeys.sorted().forEach { echo("    $it") }
        }
        if (orphans.isNotEmpty()) {
            echo("  ${orphans.size} orphaned key(s) exist in the project but not in the manifest (never deleted here):")
            orphans.forEach { echo("    $it") }
        }
        warnings.forEach { echo("  WARNING: $it") }
    }

    /** Every existing key with its source-language text/forms, paginated until a short page. */
    private suspend fun fetchExisting(
        gql: GraphQLClient,
        projectId: Uuid,
        sourceLanguage: String,
    ): Map<String, ExistingString> {
        val out = LinkedHashMap<String, ExistingString>()
        var offset = 0
        val limit = 200
        while (true) {
            val strings = gql.execute(
                GetLocalizationProjectStrings,
                GetLocalizationProjectStrings.Variables(
                    id = projectId,
                    tag = sourceLanguage,
                    offset = offset,
                    limit = limit,
                ),
            ).localization.project?.strings.orEmpty()
            for (string in strings) {
                out[string.key] = ExistingString(
                    id = string.id,
                    plural = string.plural,
                    sourceText = string.translations
                        .firstOrNull { it.languageTag.equals(sourceLanguage, ignoreCase = true) }
                        ?.text,
                    sourceForms = string.pluralTranslations
                        .associate { it.pluralCategory.name to it.text },
                )
            }
            if (strings.size < limit) break
            offset += limit
        }
        return out
    }

    private suspend fun addString(
        gql: GraphQLClient,
        projectId: Uuid,
        entry: I18nManifestString,
    ): Uuid =
        gql.execute(
            AddLocalizationString,
            AddLocalizationString.Variables(
                LocalizationStringInput(
                    context = "${entry.file}:${entry.line} (${entry.origin.lowercase()})",
                    key = entry.key,
                    placeholders = entry.placeholders
                        .takeIf { it.isNotEmpty() }
                        ?.map { placeholder ->
                            LocalizationPlaceholderInput(
                                example = placeholder.expression,
                                name = placeholder.name,
                                // The plural count is numeric by construction; other
                                // expressions carry no reliable type hint.
                                type = if (placeholder.name == "count") "number" else null,
                            )
                        },
                    plural = entry.plural,
                    projectId = projectId,
                ),
            ),
        ).localization.addString.id

    /** Seeds/updates the SOURCE-language translation from the authored text; returns rows written. */
    private suspend fun seedSource(
        gql: GraphQLClient,
        stringId: Uuid,
        sourceLanguage: String,
        entry: I18nManifestString,
    ): Int {
        var written = 0
        entry.message?.let { text ->
            gql.execute(
                SetLocalizationTranslation,
                SetLocalizationTranslation.Variables(
                    LocalizationTranslationInput(
                        languageTag = sourceLanguage,
                        origin = TranslationOrigin.IMPORT,
                        originDetail = ORIGIN_DETAIL,
                        stringId = stringId,
                        text = text,
                    ),
                ),
            )
            written++
        }
        for ((category, text) in entry.forms) {
            val pluralCategory = try {
                PluralCategory.valueOf(category.uppercase())
            } catch (_: IllegalArgumentException) {
                throw CliktError("Unsupported plural category '$category' for key '${entry.key}'.")
            }
            gql.execute(
                SetLocalizationPluralTranslation,
                SetLocalizationPluralTranslation.Variables(
                    LocalizationPluralTranslationInput(
                        languageTag = sourceLanguage,
                        origin = TranslationOrigin.IMPORT,
                        originDetail = ORIGIN_DETAIL,
                        pluralCategory = pluralCategory,
                        stringId = stringId,
                        text = text,
                    ),
                ),
            )
            written++
        }
        return written
    }

    /** Clears an existing source plural set before writing the manifest's exact category set. */
    private suspend fun deleteSourceForms(
        gql: GraphQLClient,
        stringId: Uuid,
        sourceLanguage: String,
    ) {
        gql.execute(
            DeleteLocalizationPluralTranslations,
            DeleteLocalizationPluralTranslations.Variables(stringId, sourceLanguage),
        )
    }

    private companion object {
        const val ORIGIN_DETAIL = "bosca bml i18n push"
    }
}
