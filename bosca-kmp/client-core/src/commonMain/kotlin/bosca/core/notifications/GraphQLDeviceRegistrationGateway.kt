package bosca.core.notifications

import bosca.core.graphql.AddDevicePushToken
import bosca.core.graphql.CheckInPushInstallation
import bosca.core.graphql.ClearPushDevicePrincipal
import bosca.core.graphql.DeviceInput
import bosca.core.graphql.PlatformType
import bosca.core.graphql.RegisterPushDevice
import bosca.core.graphql.RemoveDevicePushToken
import bosca.core.graphql.PushProvider
import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.execute
import kotlin.uuid.Uuid

/** Device registration gateway backed by Bosca's typed GraphQL client. */
class GraphQLDeviceRegistrationGateway(
    private val client: GraphQLClient,
) : DeviceRegistrationGateway {
    override suspend fun register(platform: PushDevicePlatform, installationId: String): RegisteredDevice {
        val device = client.execute(
            RegisterPushDevice,
            RegisterPushDevice.Variables(
                device = DeviceInput(
                    platform = platform.toGraphQL(),
                    installationId = installationId,
                ),
            ),
        ).devices.register
        return RegisteredDevice(
            id = device.id,
            installationId = checkNotNull(device.installationId) {
                "Device registration did not return the Analytics installation ID"
            },
        )
    }

    override suspend fun checkIn(installationId: String) {
        client.execute(
            CheckInPushInstallation,
            CheckInPushInstallation.Variables(installationId = installationId),
        )
    }

    override suspend fun clearPrincipal(deviceId: Uuid) {
        check(
            client.execute(
                ClearPushDevicePrincipal,
                ClearPushDevicePrincipal.Variables(id = deviceId),
            ).devices.clearPrincipal,
        ) {
            "Device was not associated with the authenticated principal"
        }
    }

    override suspend fun addPushToken(deviceId: Uuid, token: String) {
        addPushToken(deviceId, PushTokenTransport.FCM, token)
    }

    override suspend fun addPushToken(deviceId: Uuid, transport: PushTokenTransport, token: String) {
        client.execute(
            AddDevicePushToken,
            AddDevicePushToken.Variables(id = deviceId, provider = transport.toGraphQL(), token = token),
        )
    }

    override suspend fun removePushToken(deviceId: Uuid, token: String) {
        removePushToken(deviceId, PushTokenTransport.FCM, token)
    }

    override suspend fun removePushToken(deviceId: Uuid, transport: PushTokenTransport, token: String) {
        client.execute(
            RemoveDevicePushToken,
            RemoveDevicePushToken.Variables(id = deviceId, provider = transport.toGraphQL(), token = token),
        )
    }

    private fun PushDevicePlatform.toGraphQL(): PlatformType = when (this) {
        PushDevicePlatform.IOS -> PlatformType.IOS
        PushDevicePlatform.ANDROID -> PlatformType.ANDROID
        PushDevicePlatform.WEB -> PlatformType.WEB
        PushDevicePlatform.DESKTOP -> PlatformType.DESKTOP
    }

    private fun PushTokenTransport.toGraphQL(): PushProvider = when (this) {
        PushTokenTransport.FCM -> PushProvider.FCM
        PushTokenTransport.APNS -> PushProvider.APNS
    }
}
