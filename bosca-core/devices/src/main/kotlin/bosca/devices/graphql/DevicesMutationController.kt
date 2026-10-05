package bosca.devices.graphql

import bosca.devices.model.Device
import bosca.devices.model.DeviceInput
import bosca.devices.model.PushToken
import bosca.devices.model.PushProvider
import bosca.devices.service.DeviceService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/**
 * Handles mutation operations for device registration, check-in, push token management, and deletion.
 */
@TypeController
class DevicesMutationController(
    private val deviceService: DeviceService
) : GraphQLController<DevicesMutation> {

    @Field
    suspend fun register(
        authentication: AuthenticationContext,
        device: DeviceInput
    ): Device {
        val principal = authentication.principal()?.id?.takeIf { it != UUID.NIL }
            ?: throw SecurityException("not authenticated")
        return deviceService.register(
            Device(principalId = principal, platform = device.platform, installationId = device.installationId)
        )
    }

    @Field
    suspend fun checkIn(
        authentication: AuthenticationContext,
        id: UUID
    ): Device {
        val principal = authentication.principal()?.id?.takeIf { it != UUID.NIL }
            ?: throw SecurityException("not authenticated")
        val device = deviceService.getById(id) ?: throw NoSuchElementException("device not found")
        if (device.principalId != principal) throw SecurityException("not allowed")
        return deviceService.checkIn(id) ?: throw NoSuchElementException("device not found")
    }

    /** Records activity for an installation even when it is not associated with a principal. */
    @Field
    suspend fun checkInInstallation(installationId: String): Boolean {
        require(installationId.isNotBlank()) { "Analytics installation ID is required" }
        return deviceService.checkIn(installationId) != null
    }

    /** Clears the current principal association while preserving the installation and provider tokens. */
    @Field
    suspend fun clearPrincipal(
        authentication: AuthenticationContext,
        id: UUID,
    ): Boolean {
        val principal = authentication.principal()?.id?.takeIf { it != UUID.NIL }
            ?: throw SecurityException("not authenticated")
        val device = deviceService.getById(id) ?: throw NoSuchElementException("device not found")
        if (device.principalId != principal) throw SecurityException("not allowed")
        return deviceService.clearPrincipal(id, principal) != null
    }

    @Field
    suspend fun addPushToken(
        authentication: AuthenticationContext,
        id: UUID,
        provider: PushProvider,
        token: String
    ): Boolean {
        val principal = authentication.principal()?.id?.takeIf { it != UUID.NIL }
            ?: throw SecurityException("not authenticated")
        require(token.length in 1..2048) { "Invalid push token length" }
        val device = deviceService.getById(id) ?: throw NoSuchElementException("device not found")
        if (device.principalId != principal) throw SecurityException("not allowed")
        deviceService.addPushToken(PushToken(deviceId = id, token = token, provider = provider))
        return true
    }

    @Field
    suspend fun removePushToken(
        authentication: AuthenticationContext,
        id: UUID,
        provider: PushProvider,
        token: String
    ): Boolean {
        val principal = authentication.principal()?.id?.takeIf { it != UUID.NIL }
            ?: throw SecurityException("not authenticated")
        require(token.length in 1..2048) { "Invalid push token length" }
        val device = deviceService.getById(id) ?: throw NoSuchElementException("device not found")
        if (device.principalId != principal) throw SecurityException("not allowed")
        deviceService.removePushToken(id, provider, token)
        return true
    }

    @Field
    suspend fun delete(
        authentication: AuthenticationContext,
        id: UUID
    ): Boolean {
        val principal = authentication.principal()?.id?.takeIf { it != UUID.NIL }
            ?: throw SecurityException("not authenticated")
        val device = deviceService.getById(id) ?: throw NoSuchElementException("device not found")
        if (device.principalId != principal) throw SecurityException("not allowed")
        deviceService.delete(id)
        return true
    }
}
