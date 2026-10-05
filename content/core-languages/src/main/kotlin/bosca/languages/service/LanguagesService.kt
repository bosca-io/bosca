package bosca.languages.service

import bosca.languages.model.Language
import bosca.languages.model.LanguageResolutionContext
import bosca.languages.model.LanguageResolutionContextInput
import bosca.languages.model.LanguageTagMapping
import bosca.languages.model.LanguageTagMappingInput
import bosca.languages.model.LanguageTagResolution
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing the set of supported languages in the platform.
 *
 * Languages are identified by their [Language.tag] (e.g., an IETF BCP 47 language tag) and include
 * both a canonical name and a localized name.
 */
interface LanguagesService : Service {

    /**
     * Retrieves all languages currently registered in the system.
     *
     * @return a list of all [Language] entries, which may be empty if none are configured
     */
    suspend fun getAll(): List<Language>

    /**
     * Looks up a single language by its tag.
     *
     * @param tag the language tag to search for (e.g., "en", "es-MX")
     * @return the matching [Language], or `null` if no language with the given tag exists
     */
    suspend fun get(tag: String): Language?

    /**
     * Registers a new language in the system.
     *
     * @param language the language to add, including its tag, name, local name, and optional attributes
     */
    suspend fun add(language: Language)

    /**
     * Updates an existing language entry, matching by the language's [Language.tag].
     *
     * @param language the language with updated fields to persist
     */
    suspend fun edit(language: Language)

    /**
     * Removes a language from the system by its tag.
     *
     * @param tag the tag of the language to delete
     */
    suspend fun delete(tag: String)

    /** Returns every configured language-resolution context. */
    suspend fun getResolutionContexts(): List<LanguageResolutionContext>

    /** Returns the resolution context identified by [key], or `null` when it does not exist. */
    suspend fun getResolutionContext(key: String): LanguageResolutionContext?

    /** Returns all explicit mappings owned by [contextId]. */
    suspend fun getLanguageTagMappings(contextId: UUID): List<LanguageTagMapping>

    /**
     * Resolves [languageTag] using the mappings and fallback belonging to [contextKey]. Resolution checks the
     * normalized tag and then progressively less-specific parent tags. Blank and unmapped tags use the context
     * fallback.
     */
    suspend fun resolveLanguageTag(contextKey: String, languageTag: String?): LanguageTagResolution

    /**
     * Creates a language-resolution context.
     *
     * Protected contexts cannot be renamed or deleted through this service, while their display metadata,
     * fallback language, and mappings remain editable.
     */
    suspend fun addResolutionContext(
        input: LanguageResolutionContextInput,
        isProtected: Boolean = false,
    ): LanguageResolutionContext

    /** Makes the context identified by [id] resistant to renaming and deletion. */
    suspend fun protectResolutionContext(id: UUID): LanguageResolutionContext

    /** Edits the language-resolution context identified by [id], preserving any context protection. */
    suspend fun editResolutionContext(id: UUID, input: LanguageResolutionContextInput): LanguageResolutionContext

    /** Deletes the unprotected language-resolution context identified by [id] and its mappings. */
    suspend fun deleteResolutionContext(id: UUID)

    /** Creates or replaces one mapping from a Bosca source tag to a context-specific resolved tag. */
    suspend fun setLanguageTagMapping(contextId: UUID, input: LanguageTagMappingInput): LanguageTagMapping

    /** Deletes one source-tag mapping from [contextId]. */
    suspend fun deleteLanguageTagMapping(contextId: UUID, sourceLanguageTag: String)
}
