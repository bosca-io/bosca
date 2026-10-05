package bosca.core.notifications

import kotlin.uuid.Uuid

/** Identifiers returned after registering an Analytics installation as a device. */
data class RegisteredDevice(
    val id: Uuid,
    val installationId: String,
)

/** Backend operations required to maintain one installation's device and push token. */
interface DeviceRegistrationGateway {
    /**
     * Registers or refreshes the Analytics-issued [installationId].
     */
    suspend fun register(platform: PushDevicePlatform, installationId: String): RegisteredDevice

    /** Updates the uniquely identified installation's last check-in timestamp. */
    suspend fun checkIn(installationId: String)

    /** Clears the authenticated principal association while preserving the device and tokens. */
    suspend fun clearPrincipal(deviceId: Uuid)

    /** Associates a provider token with the device. */
    suspend fun addPushToken(deviceId: Uuid, token: String)

    /** Associates a provider token, preserving the issuing transport. */
    suspend fun addPushToken(deviceId: Uuid, transport: PushTokenTransport, token: String) {
        addPushToken(deviceId, token)
    }

    /** Removes a provider token from the device. */
    suspend fun removePushToken(deviceId: Uuid, token: String)

    /** Removes a provider token without affecting an identical token from another transport. */
    suspend fun removePushToken(deviceId: Uuid, transport: PushTokenTransport, token: String) {
        removePushToken(deviceId, token)
    }
}
