package bosca.configuration.repository

import bosca.configuration.model.Configuration
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface ConfigurationRepository {

    @Query("insert into configurations (id, key, description, public) values (:id, :key, :description, :public) returning *")
    suspend fun add(configuration: Configuration): Configuration

    @Query("select * from configurations")
    suspend fun getAll(): List<Configuration>

    @Query("select * from configurations where key = :key")
    suspend fun getConfigurationByKey(key: String): Configuration?

    /** Locks the configuration identity until the current transaction ends. */
    @Query("select * from configurations where id = :id for update")
    suspend fun lock(id: UUID): Configuration?

    @Query("delete from configurations where key = :key")
    suspend fun deleteConfigurationByKey(key: String)

    @Query("delete from configurations where id = :id")
    suspend fun deleteConfigurationById(id: UUID)
}
