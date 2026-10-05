package bosca.languages.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.languages.model.Language
import bosca.languages.model.LanguageInput
import bosca.languages.model.LanguageResolutionContext
import bosca.languages.model.LanguageResolutionContextInput
import bosca.languages.model.LanguageTagMapping
import bosca.languages.model.LanguageTagMappingInput
import bosca.languages.service.LanguagesService
import bosca.serialization.UUID
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

object LanguagesMutation

/**
 * Handles mutations for the system-wide supported languages registry.
 *
 * Languages added here become available as translation targets across
 * all localization projects. Requires administrator privileges because
 * changes affect referential integrity with localization translations
 * and string language tags.
 */
@TypeController
class LanguagesMutationController(
    private val service: LanguagesService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<LanguagesMutation> {

    /**
     * Registers a new language in the system-wide supported languages list.
     *
     * @param authentication the caller's security context, must have admin privileges
     * @param input the language tag (BCP 47), display name, and optional attributes
     * @return the persisted language record
     */
    @Field
    suspend fun add(authentication: AuthenticationContext, input: LanguageInput): Language {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val language = Language(tag = input.tag, name = input.name, localName = input.localName, attributes = input.attributes)
        service.add(language)
        return service.get(input.tag) ?: language
    }

    /**
     * Updates an existing language's display name or attributes.
     *
     * @param authentication the caller's security context, must have admin privileges
     * @param input the language tag to update along with its new values
     * @return the updated language record
     */
    @Field
    suspend fun edit(authentication: AuthenticationContext, input: LanguageInput): Language {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val language = Language(tag = input.tag, name = input.name, localName = input.localName, attributes = input.attributes)
        service.edit(language)
        return service.get(input.tag) ?: language
    }

    /**
     * Removes a language from the supported languages list.
     *
     * Callers should verify that no localization projects reference this tag
     * before deleting, as foreign key constraints may reject the deletion.
     *
     * @param authentication the caller's security context, must have admin privileges
     * @param tag the BCP 47 language tag to remove
     * @return true if the deletion succeeded
     */
    @Field
    suspend fun delete(authentication: AuthenticationContext, tag: String): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.delete(tag)
        return true
    }

    /** Creates a named language-resolution context. */
    @Field
    suspend fun addResolutionContext(
        authentication: AuthenticationContext,
        input: LanguageResolutionContextInput,
    ): LanguageResolutionContext {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.addResolutionContext(input)
    }

    /** Edits a named language-resolution context. */
    @Field
    suspend fun editResolutionContext(
        authentication: AuthenticationContext,
        id: UUID,
        input: LanguageResolutionContextInput,
    ): LanguageResolutionContext {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.editResolutionContext(id, input)
    }

    /** Deletes a language-resolution context and its mappings. */
    @Field
    suspend fun deleteResolutionContext(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.deleteResolutionContext(id)
        return true
    }

    /** Creates or replaces one mapping within a language-resolution context. */
    @Field
    suspend fun setLanguageTagMapping(
        authentication: AuthenticationContext,
        contextId: UUID,
        input: LanguageTagMappingInput,
    ): LanguageTagMapping {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.setLanguageTagMapping(contextId, input)
    }

    /** Deletes one mapping from a language-resolution context. */
    @Field
    suspend fun deleteLanguageTagMapping(
        authentication: AuthenticationContext,
        contextId: UUID,
        sourceLanguageTag: String,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.deleteLanguageTagMapping(contextId, sourceLanguageTag)
        return true
    }
}
