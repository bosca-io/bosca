package bosca.messages.pages.localization

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.languages.model.Language
import bosca.languages.service.LanguagesService
import bosca.localization.model.LocalizationProjectInput
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationStringInput
import bosca.localization.model.LocalizationTranslationInput
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.localization.service.LocalizationService
import java.util.Locale

/**
 * Seeds the default localized strings for UI pages and transactional emails into the
 * [LocalizationService] from the bundled `key,language,text` CSV resources
 * ([MessageLocalizationDefaults.SEED_RESOURCES]).
 *
 * The CSV carries every language currently shipped and the Bosca brand defaults (`site.name`,
 * `email.verification`) — the old hard-coded `site.name.pe` / `site.name.cas` variants are gone, so
 * the brand is a single string an admin can edit afterwards.
 *
 * Idempotent and non-destructive: each string and translation is created once. Existing translation
 * text and workflow state belong to operators and are never overwritten by a package upgrade.
 */
class MessageLocalizationInstaller(
    private val languages: LanguagesService,
    private val localization: LocalizationService,
    private val projectName: String,
) : PackageInstaller {

    override val version: String = "1.1.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val rows = readSeed()
        if (rows.isEmpty()) return

        val languagesTags = rows.mapTo(sortedSetOf()) { it.language }

        languagesTags.map { language ->
            val locale = Locale.forLanguageTag(language)
            Language(
                language,
                locale.displayName,
                locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() },
            )
        }.forEach { language ->
            if (languages.get(language.tag) == null) {
                languages.add(language)
            }
        }

        val project = localization.getProjects().find { it.name == projectName }
            ?: localization.addProject(
                LocalizationProjectInput(
                    name = projectName,
                    description = "Default localized strings for UI pages and transactional emails",
                    sourceLanguage = MessageLocalizationDefaults.SOURCE_LANGUAGE,
                )
            )

        // Declare every language present in the seed (idempotent).
        for (tag in languagesTags) {
            localization.addProjectLanguage(project.id, tag)
        }

        val stringsByKey = HashMap<String, LocalizationString>()
        for (row in rows) {
            val string = stringsByKey.getOrPut(row.key) {
                localization.getStringByKey(project.id, row.key)
                    ?: localization.addString(LocalizationStringInput(projectId = project.id, key = row.key))
            }
            if (localization.getTranslation(string.id, row.language) != null) continue
            var translation = localization.setTranslation(
                LocalizationTranslationInput(
                    stringId = string.id,
                    languageTag = row.language,
                    text = row.text,
                    origin = TranslationOrigin.IMPORT,
                    originDetail = INSTALLER_ORIGIN,
                ),
                createdBy = null,
            )

            // Transition only the row created above. Bulk transitions would approve unrelated
            // operator-authored drafts in the same project and language.
            translation = localization.transitionTranslation(translation.id, TranslationState.IN_REVIEW, null)
            translation = localization.transitionTranslation(translation.id, TranslationState.APPROVED, null)
            if (row.publish) {
                localization.transitionTranslation(translation.id, TranslationState.PUBLISHED, null)
            }
        }
    }

    private fun readSeed(): List<SeedRow> {
        return MessageLocalizationDefaults.SEED_RESOURCES.flatMap { resource ->
            readSeed(resource, publish = resource == MessageLocalizationDefaults.BML_MESSAGE_SEED_RESOURCE)
        }
    }

    private fun readSeed(resource: String, publish: Boolean): List<SeedRow> {
        val stream = javaClass.getResourceAsStream(resource) ?: return emptyList()
        val text = stream.use { it.readBytes().decodeToString() }
        val rows = CsvReader.parse(text)
        // Drop the header row (key,language,text); ignore any malformed short rows.
        return rows.drop(1).mapNotNull { cols ->
            if (cols.size < 3) null else SeedRow(cols[0], cols[1], cols[2], publish)
        }
    }

    private data class SeedRow(val key: String, val language: String, val text: String, val publish: Boolean)

    private companion object {
        const val INSTALLER_ORIGIN = "messages-localization installer"
    }
}
