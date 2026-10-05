@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationProject
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for [LocalizationProject]. Queries are schema-qualified against
 * `localization.projects`; the dedicated schema keeps localization tables off
 * the crowded `public` schema.
 */
@Repository
interface LocalizationProjectRepository {

    @Query("select * from localization.projects order by name")
    suspend fun getAll(): List<LocalizationProject>

    @Query("select * from localization.projects order by name offset :offset limit :limit")
    suspend fun getAll(offset: Int, limit: Int): List<LocalizationProject>

    @Query("select * from localization.projects where id = :id")
    suspend fun getById(id: UUID): LocalizationProject?

    @Query("select * from localization.projects where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<LocalizationProject>

    @Query("insert into localization.projects (name, description, source_language, attributes) values (:name, :description, :sourceLanguage, :attributes) returning *")
    suspend fun add(project: LocalizationProject): LocalizationProject

    @Query("update localization.projects set name = :name, description = :description, source_language = :sourceLanguage, attributes = :attributes, modified = now() where id = :id returning *")
    suspend fun update(project: LocalizationProject): LocalizationProject?

    @Query("delete from localization.projects where id = :id")
    suspend fun deleteById(id: UUID)
}
