@file:OptIn(ExperimentalUuidApi::class)

package bosca.storage.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import kotlin.uuid.ExperimentalUuidApi

@Repository
interface StorageSystemRepository {

    @Query("select * from storage_systems order by name desc")
    suspend fun getAll(): List<StorageSystem>

    @Query("select * from storage_systems where name = :name")
    suspend fun findByName(name: String): StorageSystem?

    @Query("select * from storage_systems where id = :id")
    suspend fun getById(id: UUID): StorageSystem?

    @Query("insert into storage_systems (name, type, description, configuration) values (:name, :type, :description, :configuration) returning *")
    suspend fun add(system: StorageSystem): StorageSystem

    @Query("update storage_systems set name = :name, type = :type, description = :description, configuration = :configuration where id = :id returning *")
    suspend fun update(system: StorageSystem): StorageSystem

    @Query("delete from storage_systems where id = :id")
    suspend fun deleteById(id: UUID)
}