package bosca.languages.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.languages.model.Language
import bosca.languages.model.LanguageResolutionContext
import bosca.languages.model.LanguageTagMapping
import bosca.serialization.UUID

@Repository
interface LanguagesRepository {

    @Query("select * from languages order by localname asc")
    suspend fun getAll(): List<Language>

    @Query("select * from languages where tag = :tag")
    suspend fun get(tag: String): Language?

    @Query("insert into languages (tag, name, localname, attributes) values (:tag, :name, :localName, :attributes) returning *")
    suspend fun add(language: Language): Language

    @Query("update languages set name = :name, localname = :localName, attributes = :attributes where tag = :tag returning *")
    suspend fun update(language: Language): Language

    @Query("delete from languages where tag = :tag")
    suspend fun delete(tag: String)

    @Query("select * from language_resolution_contexts order by name, key")
    suspend fun getResolutionContexts(): List<LanguageResolutionContext>

    @Query("select * from language_resolution_contexts where id = :id")
    suspend fun getResolutionContextById(id: UUID): LanguageResolutionContext?

    @Query("select * from language_resolution_contexts where key = :key")
    suspend fun getResolutionContextByKey(key: String): LanguageResolutionContext?

    @Query("select * from language_tag_mappings where context_id = :contextId order by source_language_tag")
    suspend fun getLanguageTagMappings(contextId: UUID): List<LanguageTagMapping>

    @Query("select * from language_tag_mappings where context_id = :contextId and source_language_tag = :sourceLanguageTag")
    suspend fun getLanguageTagMapping(contextId: UUID, sourceLanguageTag: String): LanguageTagMapping?

    @Query("""
        insert into language_resolution_contexts (key, name, description, fallback_language_tag, protected)
        values (:key, :name, :description, :fallbackLanguageTag, :isProtected)
        returning *
    """)
    suspend fun addResolutionContext(context: LanguageResolutionContext): LanguageResolutionContext

    @Query("update language_resolution_contexts set protected = true where id = :id returning *")
    suspend fun protectResolutionContext(id: UUID): LanguageResolutionContext

    @Query("""
        update language_resolution_contexts
        set key = :key, name = :name, description = :description, fallback_language_tag = :fallbackLanguageTag
        where id = :id
        returning *
    """)
    suspend fun updateResolutionContext(context: LanguageResolutionContext): LanguageResolutionContext

    @Query("delete from language_resolution_contexts where id = :id")
    suspend fun deleteResolutionContext(id: UUID)

    @Query("""
        insert into language_tag_mappings (context_id, source_language_tag, resolved_language_tag)
        values (:contextId, :sourceLanguageTag, :resolvedLanguageTag)
        on conflict (context_id, source_language_tag)
        do update set resolved_language_tag = excluded.resolved_language_tag
        returning *
    """)
    suspend fun setLanguageTagMapping(mapping: LanguageTagMapping): LanguageTagMapping

    @Query("delete from language_tag_mappings where context_id = :contextId and source_language_tag = :sourceLanguageTag")
    suspend fun deleteLanguageTagMapping(contextId: UUID, sourceLanguageTag: String)
}
