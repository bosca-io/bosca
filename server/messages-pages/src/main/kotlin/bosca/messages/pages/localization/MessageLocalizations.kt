package bosca.messages.pages.localization

import bosca.localization.service.LocalizationService
import bosca.pages.MapLocalization

/**
 * Builds a [MapLocalization] for a single locale by reading the message strings out of the
 * [LocalizationService] ahead of rendering.
 *
 * Email templates call `localize(key)` synchronously while rendering, so all strings for the
 * requested locale are resolved here (in a suspend context) and handed to the template as an
 * in-memory map — the same shape a `ResourceBundle` would have provided.
 */
object MessageLocalizations {

    /**
     * Loads every string in the messages project, choosing each key's text from the first
     * available locale in the fallback chain (exact tag, then base language, then the source
     * language). Throws if the project has not been seeded — there is no properties fallback.
     */
    suspend fun load(
        service: LocalizationService,
        projectName: String,
        languageTag: String,
    ): MapLocalization {
        val project = service.getProjects().find { it.name == projectName }
            ?: error(
                "Localization project \"$projectName\" not found. " +
                    "Run the messages-localization installer to seed the email defaults."
            )
        val chain = fallbackChain(languageTag)
        val strings = service.getStrings(project.id, 0, Int.MAX_VALUE)
        val resolved = HashMap<String, String>(strings.size)
        for (string in strings) {
            val byTag = service.getTranslations(string.id).associateBy { it.languageTag }
            for (tag in chain) {
                val translation = byTag[tag] ?: continue
                resolved[string.key] = translation.text
                break
            }
        }
        return MapLocalization(resolved)
    }

    /**
     * Ordered, de-duplicated lookup chain for a requested tag: the tag itself, its base
     * language, then the source language (e.g. `es-419` -> `es` -> `en`; `en-US` -> `en`).
     */
    internal fun fallbackChain(languageTag: String): List<String> =
        listOf(languageTag, languageTag.substringBefore('-'), MessageLocalizationDefaults.SOURCE_LANGUAGE)
            .distinct()
}
