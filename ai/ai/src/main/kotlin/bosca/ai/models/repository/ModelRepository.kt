package bosca.ai.models.repository

import bosca.ai.agents.git.KeyIdEntry
import bosca.ai.models.model.Model
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface ModelRepository {

    @Query("select * from models order by name")
    suspend fun getAll(): List<Model>

    @Query("select * from models where id = :id")
    suspend fun getById(id: UUID): Model

    @Query("select * from models where key = :key")
    suspend fun getByKey(key: String): Model?

    @Query("select key, id from models")
    suspend fun getKeyIndex(): List<KeyIdEntry>

    @Query("insert into models (key, type, name, description, configuration) values (:key, :type, :name, :description, :configuration) returning *")
    suspend fun add(model: Model): Model

    @Query("update models set key = :key, type = :type, name = :name, description = :description, configuration = :configuration where id = :id returning *")
    suspend fun update(model: Model): Model

    @Query("delete from models where id = :id")
    suspend fun deleteById(id: UUID)
}
