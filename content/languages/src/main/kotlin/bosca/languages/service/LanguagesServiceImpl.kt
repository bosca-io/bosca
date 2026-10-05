package bosca.languages.service

import bosca.languages.model.Language
import bosca.languages.model.LanguageResolutionContext
import bosca.languages.model.LanguageResolutionContextInput
import bosca.languages.model.LanguageTagMapping
import bosca.languages.model.LanguageTagMappingInput
import bosca.languages.model.LanguageTagResolution
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.languages.repository.LanguagesRepository
import bosca.db.transaction
import java.util.Locale

@ServiceImplementation
class LanguagesServiceImpl(
    private val repository: LanguagesRepository
) : LanguagesService {

    override suspend fun getAll(): List<Language> = repository.getAll()

    override suspend fun add(language: Language) {
        repository.add(language)
    }

    override suspend fun get(tag: String): Language? {
        return repository.get(tag)
    }

    override suspend fun edit(language: Language) {
        repository.update(language)
    }

    override suspend fun delete(tag: String) {
        repository.delete(tag)
    }

    override suspend fun getResolutionContexts(): List<LanguageResolutionContext> = repository.getResolutionContexts()

    override suspend fun getResolutionContext(key: String): LanguageResolutionContext? =
        repository.getResolutionContextByKey(normalizeContextKey(key))

    override suspend fun getLanguageTagMappings(contextId: UUID): List<LanguageTagMapping> =
        repository.getLanguageTagMappings(contextId)

    override suspend fun resolveLanguageTag(contextKey: String, languageTag: String?): LanguageTagResolution {
        val context = getResolutionContext(contextKey)
            ?: throw NoSuchElementException("Language resolution context not found: $contextKey")
        val normalized = normalizeLanguageTagOrNull(languageTag)
        val mapping = normalized?.let { findMostSpecificMapping(context.id, it) }
        return LanguageTagResolution(
            requestedLanguageTag = languageTag,
            normalizedLanguageTag = normalized,
            resolvedLanguageTag = mapping?.resolvedLanguageTag ?: context.fallbackLanguageTag,
            usedFallback = mapping == null,
        )
    }

    override suspend fun addResolutionContext(
        input: LanguageResolutionContextInput,
        isProtected: Boolean,
    ): LanguageResolutionContext = transaction {
        val context = input.toContext().copy(isProtected = isProtected)
        repository.addResolutionContext(context)
    }

    override suspend fun protectResolutionContext(id: UUID): LanguageResolutionContext = transaction {
        repository.getResolutionContextById(id)
            ?: throw NoSuchElementException("Language resolution context not found: $id")
        repository.protectResolutionContext(id)
    }

    override suspend fun editResolutionContext(
        id: UUID,
        input: LanguageResolutionContextInput,
    ): LanguageResolutionContext = transaction {
        val existing = repository.getResolutionContextById(id)
            ?: throw NoSuchElementException("Language resolution context not found: $id")
        val context = input.toContext().copy(id = id, isProtected = existing.isProtected)
        check(!existing.isProtected || context.key == existing.key) {
            "Protected language resolution context keys cannot be changed"
        }
        repository.updateResolutionContext(context)
    }

    override suspend fun deleteResolutionContext(id: UUID) = transaction {
        val existing = repository.getResolutionContextById(id)
        check(existing?.isProtected != true) {
            "Protected language resolution contexts cannot be deleted"
        }
        repository.deleteResolutionContext(id)
    }

    override suspend fun setLanguageTagMapping(
        contextId: UUID,
        input: LanguageTagMappingInput,
    ): LanguageTagMapping = transaction {
        repository.getResolutionContextById(contextId)
            ?: throw NoSuchElementException("Language resolution context not found: $contextId")
        val sourceLanguageTag = normalizeRequiredLanguageTag(input.sourceLanguageTag)
        val resolvedLanguageTag = normalizeRequiredLanguageTag(input.resolvedLanguageTag)
        repository.setLanguageTagMapping(
            LanguageTagMapping(
                contextId = contextId,
                sourceLanguageTag = sourceLanguageTag,
                resolvedLanguageTag = resolvedLanguageTag,
            ),
        )
    }

    override suspend fun deleteLanguageTagMapping(contextId: UUID, sourceLanguageTag: String) = transaction {
        repository.deleteLanguageTagMapping(contextId, normalizeRequiredLanguageTag(sourceLanguageTag))
    }

    private suspend fun findMostSpecificMapping(contextId: UUID, languageTag: String): LanguageTagMapping? {
        var candidate = languageTag
        while (true) {
            repository.getLanguageTagMapping(contextId, candidate)?.let { return it }
            val separator = candidate.lastIndexOf('-')
            if (separator < 0) return null
            candidate = candidate.substring(0, separator)
        }
    }

    private fun LanguageResolutionContextInput.toContext() = LanguageResolutionContext(
        key = normalizeContextKey(key),
        name = name.trim().ifEmpty { throw IllegalArgumentException("Language resolution context name is required") },
        description = description.trim(),
        fallbackLanguageTag = normalizeRequiredLanguageTag(fallbackLanguageTag),
    )

    private fun normalizeContextKey(key: String): String = key.trim().lowercase().also {
        require(CONTEXT_KEY.matches(it)) {
            "Language resolution context key must contain only letters, numbers, hyphens, or underscores"
        }
    }

    private fun normalizeRequiredLanguageTag(tag: String): String = normalizeLanguageTagOrNull(tag)
        ?: throw IllegalArgumentException("Language tag is required")

    private fun normalizeLanguageTagOrNull(tag: String?): String? {
        val candidate = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        return Locale.forLanguageTag(candidate).toLanguageTag()
    }

    private companion object {
        val CONTEXT_KEY = Regex("[a-z0-9][a-z0-9_-]*")
    }
}
