@file:OptIn(ExperimentalUuidApi::class)

package bosca.storage.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.storage.model.StorageSystemModel
import kotlin.uuid.ExperimentalUuidApi

@Repository
interface StorageSystemModelRepository {

    @Query("select * from storage_system_models where system_id = :systemId")
    suspend fun findBySystemId(systemId: UUID): List<StorageSystemModel>

    @Query("insert into storage_system_models (system_id, model_id, configuration) values (:systemId, :modelId, :configuration) returning *")
    suspend fun add(model: StorageSystemModel): StorageSystemModel

    @Query("delete from storage_system_models where system_id = :systemId")
    suspend fun deleteBySystemId(systemId: UUID)
}
