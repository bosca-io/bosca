package bosca.configuration.repository

import bosca.configuration.model.ConfigurationPermission
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface ConfigurationPermissionRepository {

    @Query("insert into configuration_permissions (entity_id, action, group_id) values (:entityId, :action, :groupId)")
    suspend fun add(permission: ConfigurationPermission)

    @Query("DELETE FROM configuration_permissions WHERE entity_id = :configurationId")
    suspend fun deleteByConfigurationId(configurationId: UUID)

    @Query("SELECT * FROM configuration_permissions WHERE entity_id = :configurationId")
    suspend fun findConfigurationPermissionsByConfigurationId(configurationId: UUID): List<ConfigurationPermission>
}