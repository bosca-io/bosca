package bosca.core.devices

import bosca.core.analytics.InstallationIdProvider
import bosca.core.notifications.DeviceRegistrationGateway
import bosca.core.notifications.PushDevicePlatform
import bosca.core.notifications.PushTokenTransport
import bosca.core.notifications.RegisteredDevice
import bosca.core.preferences.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class DeviceRegistrationManagerTest {

    @Test
    fun registerAssociatesTheInstallationAndExposesTheDevice() = runTest {
        val preferences = FakePreferences()
        val gateway = FakeGateway()
        val registration = registration(preferences, gateway)

        assertEquals(DEVICE_ID, registration.register())

        assertEquals(DEVICE_ID, registration.deviceId.value)
        assertEquals(
            listOf(PushDevicePlatform.ANDROID to INSTALLATION_ID),
            gateway.registrations,
        )
        assertEquals(DEVICE_ID.toString(), preferences.getString("bosca.device.id").first())
        assertEquals(DEVICE_ID.toString(), preferences.getString("bosca.push.device_id").first())
    }

    @Test
    fun checkInUsesTheInstallationIdentityAfterRegistration() = runTest {
        val gateway = FakeGateway()
        val registration = registration(gateway = gateway)
        registration.register()

        registration.checkIn()

        assertEquals(listOf(INSTALLATION_ID), gateway.checkIns)
    }

    @Test
    fun checkInDoesNothingBeforeRegistration() = runTest {
        val gateway = FakeGateway()

        registration(gateway = gateway).checkIn()

        assertEquals(emptyList(), gateway.checkIns)
    }

    @Test
    fun disconnectClearsOnlyThePrincipalAssociation() = runTest {
        val preferences = FakePreferences()
        val gateway = FakeGateway()
        val registration = registration(preferences, gateway)
        registration.register()

        registration.disconnectPrincipal()

        assertEquals(listOf(DEVICE_ID), gateway.disconnectedDevices)
        assertEquals(DEVICE_ID, registration.deviceId.value)
        assertEquals(DEVICE_ID.toString(), preferences.getString("bosca.device.id").first())
        assertEquals(emptyList(), gateway.removedTokens)
    }

    @Test
    fun disconnectFailureKeepsTheCurrentDeviceAndPropagates() = runTest {
        val gateway = FakeGateway()
        val registration = registration(gateway = gateway)
        registration.register()
        gateway.disconnectError = IllegalStateException("offline")

        assertFailsWith<IllegalStateException> { registration.disconnectPrincipal() }

        assertEquals(DEVICE_ID, registration.deviceId.value)
    }

    private fun registration(
        preferences: FakePreferences = FakePreferences(),
        gateway: FakeGateway = FakeGateway(),
    ) = DeviceRegistrationManagerImpl(
        preferences = preferences,
        gateway = gateway,
        installationIdProvider = InstallationIdProvider { INSTALLATION_ID },
        platform = PushDevicePlatform.ANDROID,
    )

    private class FakePreferences : Preferences {
        private val values = mutableMapOf<String, MutableStateFlow<String?>>()

        override fun getString(key: String): Flow<String?> =
            values.getOrPut(key) { MutableStateFlow(null) }.asStateFlow()

        override suspend fun setString(key: String, value: String?) {
            values.getOrPut(key) { MutableStateFlow(null) }.value = value
        }
    }

    private class FakeGateway : DeviceRegistrationGateway {
        val registrations = mutableListOf<Pair<PushDevicePlatform, String>>()
        val checkIns = mutableListOf<String>()
        val disconnectedDevices = mutableListOf<Uuid>()
        val removedTokens = mutableListOf<String>()
        var disconnectError: Exception? = null

        override suspend fun register(
            platform: PushDevicePlatform,
            installationId: String,
        ): RegisteredDevice {
            registrations += platform to installationId
            return RegisteredDevice(DEVICE_ID, installationId)
        }

        override suspend fun checkIn(installationId: String) {
            checkIns += installationId
        }

        override suspend fun clearPrincipal(deviceId: Uuid) {
            disconnectError?.let { throw it }
            disconnectedDevices += deviceId
        }

        override suspend fun addPushToken(deviceId: Uuid, token: String) = Unit

        override suspend fun removePushToken(deviceId: Uuid, token: String) {
            removedTokens += token
        }

        override suspend fun removePushToken(
            deviceId: Uuid,
            transport: PushTokenTransport,
            token: String,
        ) {
            removedTokens += token
        }
    }

    private companion object {
        val DEVICE_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000123")
        const val INSTALLATION_ID = "01ANALYTICSINSTALLATION001"
    }
}
