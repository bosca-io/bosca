package bosca.content.metadata.repository

import bosca.content.metadata.model.BibleLanguage
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface BibleLanguageRepository {

    @Query("insert into bible_languages (metadata_id, version, variant, iso, name, name_local, script, script_code, script_direction, sort) values (:metadataId, :version, :variant, :iso, :name, :nameLocal, :script, :scriptCode, :scriptDirection, :sort)")
    suspend fun add(language: BibleLanguage)

    @Query("select * from bible_languages where metadata_id = :id and version = :version and variant = :variant")
    suspend fun getById(id: UUID, version: Int, variant: String): List<BibleLanguage>
}