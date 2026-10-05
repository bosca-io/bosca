package bosca.configuration.repository

import bosca.configuration.model.ConfigurationValue
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface ConfigurationValueRepository {

    @Query("select * from configuration_values where configuration_id = :configurationId")
    suspend fun getById(configurationId: UUID): ConfigurationValue?

    @Query("delete from configuration_values where configuration_id = :configurationId")
    suspend fun deleteById(configurationId: UUID)

    @Query("insert into configuration_values (configuration_id, value, nonce) values (:configurationId, :value, :nonce) returning *")
    suspend fun add(configurationValue: ConfigurationValue): ConfigurationValue
}