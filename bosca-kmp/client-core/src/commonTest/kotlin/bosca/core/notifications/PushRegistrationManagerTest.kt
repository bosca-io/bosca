package bosca.core.notifications

import bosca.core.devices.DeviceRegistrationManager
import bosca.core.preferences.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationManagerTest {

    @Test
    fun initializeDoesNotRegisterOrRequireADevice() = runTest {
        val deviceRegistration = FakeDeviceRegistrationManager()
        val gateway = FakeGateway()
        val manager = independentManager(
            deviceRegistration = deviceRegistration,
            gateway = gateway,
        )

        manager.initialize()
        runCurrent()

        assertEquals(0, deviceRegistration.registerCount)
        assertEquals(1, manager.notificationTypes.synchronizations)
        assertTrue(gateway.addedTokens.isEmpty())
        assertIs<PushRegistrationState.Unregistered>(manager.state.value)
    }

    @Test
    fun deviceAssociationSynchronizesThePendingProviderToken() = runTest {
        val deviceRegistration = FakeDeviceRegistrationManager()
        val gateway = FakeGateway()
        val manager = independentManager(
            deviceRegistration = deviceRegistration,
            gateway = gateway,
        )
        manager.initialize()
        runCurrent()

        deviceRegistration.associate(DEVICE_ID)
        runCurrent()

        assertEquals(
            listOf(Triple(DEVICE_ID, PushTokenTransport.FCM, "first")),
            gateway.addedTokens,
        )
        assertIs<PushRegistrationState.Registered>(manager.state.value)
    }

    @Test
    fun principalDisconnectDoesNotInterruptPushTokenRotation() = runTest {
        val deviceRegistration = FakeDeviceRegistrationManager(DEVICE_ID)
        val gateway = FakeGateway()
        val provider = FakeTokenProvider(token = "first")
        val manager = independentManager(
            deviceRegistration = deviceRegistration,
            gateway = gateway,
            provider = provider,
        )
        manager.initialize()
        runCurrent()

        deviceRegistration.disconnectPrincipal()
        provider.rotate("second")
        runCurrent()

        assertEquals(Triple(DEVICE_ID, PushTokenTransport.FCM, "second"), gateway.addedTokens.last())
        assertEquals(
            listOf(Triple(DEVICE_ID, PushTokenTransport.FCM, "first")),
            gateway.removedTokens,
        )
        assertIs<PushRegistrationState.Registered>(manager.state.value)
    }

    @Test
    fun unsupportedPlatformRemainsUnavailable() = runTest {
        val manager = independentManager(provider = FakeTokenProvider(supported = false))

        manager.initialize()

        assertIs<PushRegistrationState.Unavailable>(manager.state.value)
        assertFalse(manager.supported)
    }

    @Test
    fun grantedPermissionSynchronizesTheProviderToken() = runTest {
        val provider = FakeTokenProvider(permissionGranted = true)
        val gateway = FakeGateway()
        val manager = independentManager(provider = provider, gateway = gateway)

        assertTrue(manager.requestPermission())

        assertEquals(1, provider.permissionRequests)
        assertEquals(listOf(Triple(DEVICE_ID, PushTokenTransport.FCM, "first")), gateway.addedTokens)
    }

    @Test
    fun deniedOrUnsupportedPermissionDoesNotSynchronizeTheProviderToken() = runTest {
        val deniedProvider = FakeTokenProvider(permissionGranted = false)
        val deniedGateway = FakeGateway()
        val deniedManager = independentManager(provider = deniedProvider, gateway = deniedGateway)

        assertFalse(deniedManager.requestPermission())
        assertTrue(deniedGateway.addedTokens.isEmpty())

        val unsupportedProvider = FakeTokenProvider(permissionSupported = false)
        val unsupportedManager = independentManager(provider = unsupportedProvider)
        assertFalse(unsupportedManager.requestPermission())
        assertEquals(0, unsupportedProvider.permissionRequests)
    }

    @Test
    fun registeredDeviceAwaitsProviderToken() = runTest {
        val gateway = FakeGateway()
        val manager = independentManager(gateway = gateway, provider = FakeTokenProvider(token = null))

        manager.initialize()

        assertTrue(gateway.addedTokens.isEmpty())
        assertIs<PushRegistrationState.AwaitingToken>(manager.state.value)
    }

    @Test
    fun nativeRegistrationFailureAllowsRetry() = runTest {
        val provider = FakeTokenProvider(token = null)
        val manager = independentManager(provider = provider)

        manager.initialize()
        runCurrent()
        provider.failRegistration("APNs registration failed")
        runCurrent()

        assertEquals(PushRegistrationState.Failed("APNs registration failed"), manager.state.value)

        provider.token = "retry-token"
        manager.synchronize()

        assertIs<PushRegistrationState.Registered>(manager.state.value)
    }

    @Test
    fun tokenRotationRemovesPreviousTokenAndRegistersReplacement() = runTest {
        val gateway = FakeGateway()
        val provider = FakeTokenProvider(token = "first")
        val manager = independentManager(gateway = gateway, provider = provider)
        manager.initialize()
        runCurrent()

        provider.rotate("second")
        runCurrent()

        assertEquals(listOf(Triple(DEVICE_ID, PushTokenTransport.FCM, "first")), gateway.removedTokens)
        assertEquals(Triple(DEVICE_ID, PushTokenTransport.FCM, "second"), gateway.addedTokens.last())
        assertIs<PushRegistrationState.Registered>(manager.state.value)
    }

    @Test
    fun registrationFailuresAreVisibleAndPropagated() = runTest {
        val gateway = FakeGateway().apply { addError = IllegalStateException("offline") }
        val manager = independentManager(gateway = gateway)

        val error = assertFailsWith<IllegalStateException> { manager.initialize() }

        assertEquals("offline", error.message)
        assertEquals(PushRegistrationState.Failed("offline"), manager.state.value)
    }

    @Test
    fun cancellationIsNotConvertedToFailure() = runTest {
        val gateway = FakeGateway().apply { addError = CancellationException("cancelled") }
        val manager = independentManager(gateway = gateway)

        assertFailsWith<CancellationException> { manager.initialize() }
        assertIs<PushRegistrationState.Registering>(manager.state.value)
    }

    @Test
    fun tokenObserverContinuesAfterTransientSynchronizationFailure() = runTest {
        val gateway = FakeGateway()
        val provider = FakeTokenProvider(token = "first")
        val manager = independentManager(gateway = gateway, provider = provider)
        manager.initialize()
        runCurrent()
        gateway.addError = IllegalStateException("temporary outage")

        provider.rotate("second")
        runCurrent()
        assertEquals(PushRegistrationState.Failed("temporary outage"), manager.state.value)

        provider.rotate("third")
        runCurrent()
        assertIs<PushRegistrationState.Registered>(manager.state.value)
        assertEquals(Triple(DEVICE_ID, PushTokenTransport.FCM, "third"), gateway.addedTokens.last())
        assertEquals(listOf(Triple(DEVICE_ID, PushTokenTransport.FCM, "first")), gateway.removedTokens)
    }

    private fun kotlinx.coroutines.test.TestScope.independentManager(
        preferences: FakePreferences = FakePreferences(),
        gateway: FakeGateway = FakeGateway(),
        provider: FakeTokenProvider = FakeTokenProvider(),
        deviceRegistration: FakeDeviceRegistrationManager = FakeDeviceRegistrationManager(DEVICE_ID),
    ): TestPushRegistrationManager {
        val notificationTypes = FakeNotificationTypes()
        val manager = PushRegistrationManagerImpl(
            preferences = preferences,
            gateway = gateway,
            tokenProvider = provider,
            deviceRegistration = deviceRegistration,
            notificationTypes = notificationTypes,
            scope = backgroundScope,
        )
        return TestPushRegistrationManager(manager, notificationTypes)
    }

    private class TestPushRegistrationManager(
        private val delegate: PushRegistrationManager,
        val notificationTypes: FakeNotificationTypes,
    ) : PushRegistrationManager by delegate

    private class FakeNotificationTypes : NotificationTypeManager {
        override val types: StateFlow<List<NotificationTypeDefinition>> = MutableStateFlow(emptyList())
        var synchronizations = 0

        override suspend fun synchronize() {
            synchronizations++
        }
    }

    private class FakePreferences : Preferences {
        private val values = mutableMapOf<String, MutableStateFlow<String?>>()

        override fun getString(key: String): Flow<String?> =
            values.getOrPut(key) { MutableStateFlow(null) }.asStateFlow()

        override suspend fun setString(key: String, value: String?) {
            values.getOrPut(key) { MutableStateFlow(null) }.value = value
        }
    }

    private class FakeTokenProvider(
        override val supported: Boolean = true,
        override val permissionSupported: Boolean = supported,
        private val permissionGranted: Boolean = true,
        var token: String? = "first",
    ) : PushTokenProvider {
        private val mutableTokenChanges = MutableSharedFlow<String>(extraBufferCapacity = 1)
        private val mutableRegistrationFailures = MutableSharedFlow<String>(extraBufferCapacity = 1)
        override val platform: PushDevicePlatform = PushDevicePlatform.ANDROID
        override val tokenChanges: Flow<String> = mutableTokenChanges.asSharedFlow()
        override val registrationFailures: Flow<String> = mutableRegistrationFailures.asSharedFlow()
        override val messages: Flow<PushMessage> = MutableSharedFlow()
        var permissionRequests = 0
        override suspend fun currentToken(): String? = token

        override suspend fun deleteToken() {
            token = null
        }

        override suspend fun requestPermission(): Boolean {
            permissionRequests++
            return permissionGranted
        }

        fun rotate(token: String) {
            this.token = token
            check(mutableTokenChanges.tryEmit(token))
        }

        fun failRegistration(message: String) {
            check(mutableRegistrationFailures.tryEmit(message))
        }
    }

    private class FakeDeviceRegistrationManager(deviceId: Uuid? = null) : DeviceRegistrationManager {
        private val mutableDeviceId = MutableStateFlow(deviceId)
        override val deviceId: StateFlow<Uuid?> = mutableDeviceId.asStateFlow()
        var registerCount = 0

        override suspend fun register(): Uuid {
            registerCount++
            mutableDeviceId.value = DEVICE_ID
            return DEVICE_ID
        }

        override suspend fun checkIn() = Unit

        override suspend fun disconnectPrincipal() {
            // Principal association is independent of the registered device and push token.
        }

        fun associate(deviceId: Uuid) {
            mutableDeviceId.value = deviceId
        }
    }

    private class FakeGateway : DeviceRegistrationGateway {
        val addedTokens = mutableListOf<Triple<Uuid, PushTokenTransport, String>>()
        val removedTokens = mutableListOf<Triple<Uuid, PushTokenTransport, String>>()
        var addError: Exception? = null

        override suspend fun register(
            platform: PushDevicePlatform,
            installationId: String,
        ): RegisteredDevice = RegisteredDevice(DEVICE_ID, installationId)

        override suspend fun checkIn(installationId: String) = Unit

        override suspend fun clearPrincipal(deviceId: Uuid) = Unit

        override suspend fun addPushToken(deviceId: Uuid, token: String) {
            addPushToken(deviceId, PushTokenTransport.FCM, token)
        }

        override suspend fun addPushToken(
            deviceId: Uuid,
            transport: PushTokenTransport,
            token: String,
        ) {
            addError?.let {
                addError = null
                throw it
            }
            addedTokens += Triple(deviceId, transport, token)
        }

        override suspend fun removePushToken(deviceId: Uuid, token: String) {
            removePushToken(deviceId, PushTokenTransport.FCM, token)
        }

        override suspend fun removePushToken(
            deviceId: Uuid,
            transport: PushTokenTransport,
            token: String,
        ) {
            removedTokens += Triple(deviceId, transport, token)
        }
    }

    private companion object {
        val DEVICE_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000123")
    }
}
