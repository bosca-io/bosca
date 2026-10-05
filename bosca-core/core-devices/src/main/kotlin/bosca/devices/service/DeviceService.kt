package bosca.devices.service

import bosca.devices.model.Device
import bosca.devices.model.PushToken
import bosca.devices.model.PushProvider
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages installation-owned device registrations and their associated push notification tokens.
 *
 * Devices represent client installations (mobile apps, web browsers, desktops) and are
 * optionally associated with the principal currently signed in on that installation. Each device
 * can have one or more push tokens that remain valid when the principal association is cleared.
 */
interface DeviceService : Service {

    /**
     * Retrieves a device by its unique identifier.
     *
     * @param id the device identifier
     * @return the device, or null if not found
     */
    suspend fun getById(id: UUID): Device?

    /**
     * Retrieves all devices registered to a specific principal.
     *
     * @param principalId the principal (user account) identifier
     * @return all devices belonging to the principal
     */
    suspend fun getByPrincipal(principalId: UUID): List<Device>

    /**
     * Batch-loads devices for multiple principals into a [Batch] keyed by principal ID.
     *
     * Used for efficient bulk lookups when sending push notifications to many recipients.
     *
     * @param batch the batch to populate, keyed by principal IDs
     */
    suspend fun addDevicesToBatch(batch: Batch<UUID, List<Device>>)

    /**
     * Registers a new device for a principal.
     *
     * [Device.installationId] must have been issued by the Analytics installation endpoint.
     * Devices consumes that shared installation identity and never generates a replacement.
     *
     * @param device the device to register
     * @return the persisted device with its assigned device identifier
     */
    suspend fun register(device: Device): Device

    /**
     * Records a check-in for a device, updating its last activity timestamp.
     *
     * @param id the device identifier
     * @return the updated device, or null if not found
     */
    suspend fun checkIn(id: UUID): Device?

    /**
     * Records installation activity without requiring a principal association.
     *
     * The Analytics-issued [installationId] uniquely identifies the device even after sign-out.
     *
     * @param installationId the Analytics installation identifier
     * @return the updated device, or null if the installation has not been registered
     */
    suspend fun checkIn(installationId: String): Device?

    /**
     * Clears the current principal association without deleting the device or its push tokens.
     *
     * @param id the device identifier
     * @param principalId the principal currently associated with the device
     * @return the updated device, or null when the device is not associated with that principal
     */
    suspend fun clearPrincipal(id: UUID, principalId: UUID): Device?

    /**
     * Registers a push notification token for a device.
     *
     * @param pushToken the token to register
     */
    suspend fun addPushToken(pushToken: PushToken)

    /**
     * Removes a push notification token from a specific device.
     *
     * @param deviceId the device that owns the token
     * @param token the provider token to remove
     */
    suspend fun removePushToken(deviceId: UUID, token: String)

    /**
     * Removes a push token issued by a specific provider from a device.
     *
     * @param deviceId the device that owns the token
     * @param provider the service that issued the token
     * @param token the provider token to remove
     */
    suspend fun removePushToken(deviceId: UUID, provider: PushProvider, token: String) {
        removePushToken(deviceId, token)
    }

    /**
     * Removes provider tokens that have been reported as permanently invalid.
     *
     * This operation is used by the communications delivery path, where only the
     * raw provider token is available after a rejected send.
     *
     * @param tokens the invalid provider tokens to remove from every device
     */
    suspend fun removePushTokens(tokens: List<String>)

    /**
     * Removes tokens reported invalid by one provider without affecting an identical token
     * string issued by another provider.
     *
     * @param provider the service that rejected the tokens
     * @param tokens the invalid provider tokens to remove
     */
    suspend fun removePushTokens(provider: PushProvider, tokens: List<String>) {
        removePushTokens(tokens)
    }

    /**
     * Retrieves all push tokens registered to a specific device.
     *
     * @param id the device identifier
     * @return all push tokens for the device
     */
    suspend fun getPushTokens(id: UUID): List<PushToken>

    /**
     * Batch-loads push tokens for multiple devices into a [Batch] keyed by device ID.
     *
     * @param batch the batch to populate, keyed by device IDs
     */
    suspend fun addPushTokensToBatch(batch: Batch<UUID, List<PushToken>>)

    /**
     * Deletes a device and all of its associated push tokens.
     *
     * @param id the device identifier to delete
     */
    suspend fun delete(id: UUID)
}
