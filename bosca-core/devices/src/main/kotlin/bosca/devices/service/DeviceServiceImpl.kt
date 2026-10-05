package bosca.devices.service

import bosca.devices.model.Device
import bosca.devices.model.PushToken
import bosca.devices.model.PushProvider
import bosca.devices.repository.DeviceRepository
import bosca.devices.repository.PushTokenRemoval
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Manages device registrations and push token lifecycle by delegating
 * persistence to the [DeviceRepository].
 */
@ServiceImplementation
class DeviceServiceImpl(
    private val deviceRepository: DeviceRepository
) : DeviceService {

    override suspend fun getById(id: UUID): Device? {
        return deviceRepository.getById(id)
    }

    override suspend fun getByPrincipal(principalId: UUID): List<Device> {
        return deviceRepository.getByPrincipal(principalId)
    }

    override suspend fun addDevicesToBatch(batch: Batch<UUID, List<Device>>) {
        val devices = deviceRepository.getByPrincipals(batch.keys)
        val deviceMap = devices.groupBy { it.principalId }
        batch.keys.forEach { principalId ->
            batch.setData(principalId, deviceMap[principalId] ?: emptyList())
        }
    }

    override suspend fun register(device: Device): Device {
        require(!device.installationId.isNullOrBlank()) { "Analytics installation ID is required" }
        return deviceRepository.register(device)
    }

    override suspend fun checkIn(id: UUID): Device? {
        return deviceRepository.checkIn(id)
    }

    override suspend fun checkIn(installationId: String): Device? {
        require(installationId.isNotBlank()) { "Analytics installation ID is required" }
        return deviceRepository.checkInByInstallationId(installationId)
    }

    override suspend fun clearPrincipal(id: UUID, principalId: UUID): Device? {
        return deviceRepository.clearPrincipal(id, principalId)
    }

    override suspend fun getPushTokens(id: UUID): List<PushToken> {
        return deviceRepository.getPushTokens(id)
    }

    override suspend fun addPushTokensToBatch(batch: Batch<UUID, List<PushToken>>) {
        val tokens = deviceRepository.getPushTokens(batch.keys)
        val tokenMap = tokens.groupBy { it.deviceId }
        batch.keys.forEach { deviceId ->
            batch.setData(deviceId, tokenMap[deviceId] ?: emptyList())
        }
    }

    override suspend fun addPushToken(pushToken: PushToken) {
        deviceRepository.addPushToken(pushToken)
    }

    override suspend fun removePushToken(deviceId: UUID, token: String) {
        removePushToken(deviceId, PushProvider.FCM, token)
    }

    override suspend fun removePushToken(deviceId: UUID, provider: PushProvider, token: String) {
        deviceRepository.removePushToken(deviceId, provider.name.lowercase(), token)
    }

    override suspend fun removePushTokens(tokens: List<String>) {
        removePushTokens(PushProvider.FCM, tokens)
    }

    override suspend fun removePushTokens(provider: PushProvider, tokens: List<String>) {
        if (tokens.isNotEmpty()) {
            deviceRepository.removePushTokens(PushTokenRemoval(provider.name.lowercase(), tokens.distinct()))
        }
    }

    override suspend fun delete(id: UUID) {
        deviceRepository.deleteById(id)
    }
}
