package bosca.languages.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.languages.model.Language
import bosca.languages.model.LanguageResolutionContextInput
import bosca.languages.model.LanguageTagMapping
import bosca.languages.model.LanguageTagMappingInput
import bosca.languages.service.LanguagesService
import java.util.Locale

class LanguagePackageInstaller(
    private val languages: LanguagesService,
) : PackageInstaller {
    override val version: String = "1.2.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        listOf("en", "es", "es-419", "pt-BR", "fr", "hi", "pl", "tl", "ru", "nl", "ko", "te", "sw").map { language ->
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
        installBibleResolutionContext()
    }

    private suspend fun installBibleResolutionContext() {
        var context = languages.getResolutionContext(BIBLE_CONTEXT_KEY)
            ?: languages.addResolutionContext(
                LanguageResolutionContextInput(
                    key = BIBLE_CONTEXT_KEY,
                    name = "Bibles",
                    description = "Maps Bosca locale tags to the ISO 639-3 language codes used by Bibles.",
                    fallbackLanguageTag = DEFAULT_BIBLE_LANGUAGE_TAG,
                ),
                isProtected = true,
            )
        if (!context.isProtected) {
            context = languages.protectResolutionContext(context.id)
        }
        if (context.fallbackLanguageTag == LEGACY_DEFAULT_LANGUAGE_TAG) {
            languages.editResolutionContext(
                context.id,
                LanguageResolutionContextInput(
                    key = context.key,
                    name = context.name,
                    description = context.description,
                    fallbackLanguageTag = DEFAULT_BIBLE_LANGUAGE_TAG,
                ),
            )
        }

        val supportedLanguages = languages.getAll()
        val mappings = supportedLanguages
            .sortedBy { it.tag }
            .mapNotNull { language ->
                iso3Language(language.tag)?.let { iso3 -> language.tag to iso3 }
            }
            .toMap(linkedMapOf())
        mappings["en"]?.let { mappings.putIfAbsent("en-US", it) }

        val supportedByTag = supportedLanguages.associateBy { it.tag.lowercase() }
        val existingMappings = languages.getLanguageTagMappings(context.id)
        existingMappings
            .filter { it.sourceLanguageTag !in mappings && it.isLegacyInverseMapping(supportedByTag) }
            .forEach { languages.deleteLanguageTagMapping(context.id, it.sourceLanguageTag) }

        val existingBySource = existingMappings.associateBy { it.sourceLanguageTag }
        mappings.forEach { (source, resolved) ->
            val existing = existingBySource[source]
            if (existing == null || existing.resolvedLanguageTag == source || existing.isLegacyInverseMapping(supportedByTag)) {
                languages.setLanguageTagMapping(
                    context.id,
                    LanguageTagMappingInput(
                        sourceLanguageTag = source,
                        resolvedLanguageTag = resolved,
                    ),
                )
            }
        }
    }

    private fun LanguageTagMapping.isLegacyInverseMapping(
        supportedByTag: Map<String, Language>,
    ): Boolean {
        val legacyTarget = supportedByTag[resolvedLanguageTag.lowercase()] ?: return false
        return iso3Language(legacyTarget.tag).equals(sourceLanguageTag, ignoreCase = true)
    }

    private fun iso3Language(tag: String): String? =
        runCatching { Locale.forLanguageTag(tag).isO3Language }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val BIBLE_CONTEXT_KEY = "bibles"
        const val DEFAULT_BIBLE_LANGUAGE_TAG = "eng"
        const val LEGACY_DEFAULT_LANGUAGE_TAG = "en"
    }
}
