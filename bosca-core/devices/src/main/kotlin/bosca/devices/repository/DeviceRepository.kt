package bosca.devices.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.devices.model.Device
import bosca.devices.model.PushToken
import bosca.serialization.UUID

/**
 * Persists and retrieves device registrations and their associated push notification tokens.
 */
@Repository
interface DeviceRepository {

    @Query("select * from devices.devices where id = :id")
    suspend fun getById(id: UUID): Device?

    @Query("select * from devices.devices where principal_id = :principalId order by id")
    suspend fun getByPrincipal(principalId: UUID): List<Device>

    @Query("select * from devices.devices where principal_id = any(:principalIds) order by id")
    suspend fun getByPrincipals(principalIds: List<UUID>): List<Device>

    @Query("insert into devices.devices(principal_id, platform, installation_id, created, modified, last_check_in) values (:principalId, (:platform)::devices.platform_type, :installationId, now(), now(), now()) on conflict (installation_id) do update set principal_id = excluded.principal_id, platform = excluded.platform, modified = now(), last_check_in = now() returning *")
    suspend fun register(device: Device): Device

    @Query("update devices.devices set last_check_in = now(), modified = now() where id = :id returning *")
    suspend fun checkIn(id: UUID): Device?

    @Query("update devices.devices set last_check_in = now(), modified = now() where installation_id = :installationId returning *")
    suspend fun checkInByInstallationId(installationId: String): Device?

    @Query("update devices.devices set principal_id = null, modified = now() where id = :id and principal_id = :principalId returning *")
    suspend fun clearPrincipal(id: UUID, principalId: UUID): Device?

    @Query("insert into devices.device_push_tokens (device_id, token, provider, created) values (:deviceId, :token, (:provider)::devices.push_provider_type, :created) on conflict (provider, token) do update set device_id = excluded.device_id, created = excluded.created")
    suspend fun addPushToken(token: PushToken)

    @Query("delete from devices.device_push_tokens where device_id = :deviceId and provider = (:provider)::devices.push_provider_type and token = :token")
    suspend fun removePushToken(deviceId: UUID, provider: String, token: String)

    @Query("delete from devices.device_push_tokens where provider = (:provider)::devices.push_provider_type and token = any(:tokens)")
    suspend fun removePushTokens(removal: PushTokenRemoval)

    @Query("select * from devices.device_push_tokens where device_id = :deviceId order by created desc")
    suspend fun getPushTokens(deviceId: UUID): List<PushToken>

    @Query("select * from devices.device_push_tokens where device_id = any(:deviceIds) order by created desc")
    suspend fun getPushTokens(deviceIds: List<UUID>): List<PushToken>

    @Query("delete from devices.devices where id = :id")
    suspend fun deleteById(id: UUID)
}

data class PushTokenRemoval(
    val provider: String,
    val tokens: List<String>,
)
