package bosca.core.notifications

import bosca.core.devices.DeviceRegistrationManager
import bosca.core.platform.Log
import bosca.core.preferences.Preferences
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns provider-token registration for one application installation. Device registration and
 * principal association are delegated to [DeviceRegistrationManager].
 */
class PushRegistrationManagerImpl(
    private val preferences: Preferences,
    private val gateway: DeviceRegistrationGateway,
    private val tokenProvider: PushTokenProvider,
    private val deviceRegistration: DeviceRegistrationManager,
    private val notificationTypes: NotificationTypeManager,
    private val scope: CoroutineScope,
) : PushRegistrationManager {
    private val synchronizationMutex = Mutex()
    private var tokenChangesJob: Job? = null
    private val mutableState = MutableStateFlow<PushRegistrationState>(
        if (tokenProvider.supported) PushRegistrationState.Unregistered else PushRegistrationState.Unavailable,
    )

    override val state: StateFlow<PushRegistrationState> = mutableState.asStateFlow()

    override val messages = tokenProvider.messages

    override val supported: Boolean get() = tokenProvider.supported

    override val permissionSupported: Boolean get() = tokenProvider.permissionSupported

    override val presentsForegroundNotifications: Boolean
        get() = tokenProvider.presentsForegroundNotifications

    override suspend fun initialize() {
        if (!tokenProvider.supported) {
            mutableState.value = PushRegistrationState.Unavailable
            return
        }
        try {
            notificationTypes.synchronize()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Channel refresh is recoverable: an existing native catalog can continue presenting
            // notifications while token registration is reconciled independently.
            Log.e("Failed to synchronize notification types", e)
        }
        startTokenObservation()
        synchronize(token = null, force = true)
    }

    override suspend fun synchronize() {
        synchronize(token = null, force = true)
    }

    override suspend fun requestPermission(): Boolean {
        if (!supported || !permissionSupported || !tokenProvider.requestPermission()) return false
        synchronize()
        return true
    }

    private fun startTokenObservation() {
        if (tokenChangesJob?.isActive == true) return
        tokenChangesJob = scope.launch {
            launch {
                tokenProvider.tokenChanges.collect { token ->
                    try {
                        synchronize(token = token, force = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // synchronize records the failed state; keep collecting so a later
                        // provider rotation can recover without requiring an app restart.
                        Log.e("Failed to synchronize rotated push token", e)
                    }
                }
            }
            launch {
                tokenProvider.registrationFailures.collect { message ->
                    mutableState.value = PushRegistrationState.Failed(message)
                }
            }
            launch {
                deviceRegistration.deviceId.collect { deviceId ->
                    if (deviceId == null) {
                        mutableState.value = PushRegistrationState.Unregistered
                    } else {
                        try {
                            synchronize(token = null, force = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("Failed to synchronize push for the registered device", e)
                        }
                    }
                }
            }
        }
    }

    private suspend fun synchronize(token: String?, force: Boolean) {
        synchronizationMutex.withLock {
            val deviceId = deviceRegistration.deviceId.value
            if (deviceId == null) {
                mutableState.value = PushRegistrationState.Unregistered
                return
            }
            mutableState.value = PushRegistrationState.Registering
            try {
                val currentToken = (token ?: tokenProvider.currentToken())?.takeIf { it.isNotBlank() }
                if (currentToken == null) {
                    mutableState.value = PushRegistrationState.AwaitingToken
                    return
                }

                val previousToken = preferences.getString(TOKEN_KEY).first()?.takeIf { it.isNotBlank() }
                val previousTransport = storedTokenTransport()
                val previousDeviceId = storedTokenDeviceId()
                if (!force && previousToken == currentToken && previousDeviceId == deviceId &&
                    previousTransport == tokenProvider.transport
                ) {
                    mutableState.value = PushRegistrationState.Registered
                    return
                }
                gateway.addPushToken(deviceId, tokenProvider.transport, currentToken)
                if (previousToken != null && previousDeviceId != null &&
                    (previousToken != currentToken || previousDeviceId != deviceId ||
                        previousTransport != tokenProvider.transport)
                ) {
                    gateway.removePushToken(previousDeviceId, previousTransport, previousToken)
                }
                preferences.setString(TOKEN_KEY, currentToken)
                preferences.setString(TOKEN_TRANSPORT_KEY, tokenProvider.transport.name)
                preferences.setString(TOKEN_DEVICE_ID_KEY, deviceId.toString())
                mutableState.value = PushRegistrationState.Registered
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = PushRegistrationState.Failed(e.message ?: "Push registration failed")
                throw e
            }
        }
    }

    private suspend fun storedTokenDeviceId(): Uuid? = preferences.getString(TOKEN_DEVICE_ID_KEY).first()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

    private suspend fun storedTokenTransport(): PushTokenTransport =
        preferences.getString(TOKEN_TRANSPORT_KEY).first()
            ?.let { value -> PushTokenTransport.entries.firstOrNull { it.name == value } }
            ?: PushTokenTransport.FCM

    private companion object {
        const val PREFIX = "bosca.push"
        const val TOKEN_KEY = "$PREFIX.token"
        const val TOKEN_TRANSPORT_KEY = "$PREFIX.token_transport"
        const val TOKEN_DEVICE_ID_KEY = "$PREFIX.token_device_id"
    }
}
