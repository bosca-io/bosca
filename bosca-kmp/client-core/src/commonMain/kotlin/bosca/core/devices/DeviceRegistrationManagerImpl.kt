package bosca.core.devices

import bosca.core.analytics.InstallationIdProvider
import bosca.core.notifications.DeviceRegistrationGateway
import bosca.core.notifications.PushDevicePlatform
import bosca.core.preferences.Preferences
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable device lifecycle backed by the Analytics installation identity. */
class DeviceRegistrationManagerImpl(
    private val preferences: Preferences,
    private val gateway: DeviceRegistrationGateway,
    private val installationIdProvider: InstallationIdProvider,
    private val platform: PushDevicePlatform,
) : DeviceRegistrationManager {
    private val mutex = Mutex()
    private val mutableDeviceId = MutableStateFlow<Uuid?>(null)

    override val deviceId: StateFlow<Uuid?> = mutableDeviceId.asStateFlow()

    override suspend fun register(): Uuid = mutex.withLock {
        val installationId = installationIdProvider.getOrCreate()
        val device = gateway.register(platform, installationId)
        check(device.installationId == installationId) {
            "Device registration returned a different Analytics installation ID"
        }
        preferences.setString(DEVICE_ID_KEY, device.id.toString())
        preferences.setString(LEGACY_DEVICE_ID_KEY, device.id.toString())
        mutableDeviceId.value = device.id
        device.id
    }

    override suspend fun checkIn() {
        if (storedDeviceId() == null) return
        gateway.checkIn(installationIdProvider.getOrCreate())
    }

    override suspend fun disconnectPrincipal() {
        mutex.withLock {
            storedDeviceId()?.let { gateway.clearPrincipal(it) }
        }
    }

    private suspend fun storedDeviceId(): Uuid? =
        (preferences.getString(DEVICE_ID_KEY).first()
            ?: preferences.getString(LEGACY_DEVICE_ID_KEY).first())
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

    private companion object {
        const val DEVICE_ID_KEY = "bosca.device.id"
        const val LEGACY_DEVICE_ID_KEY = "bosca.push.device_id"
    }
}
