package bosca.core.devices

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle for associating this app installation with the current principal. */
interface DeviceRegistrationManager {
    /** The backend device registered for this app installation, if any. */
    val deviceId: StateFlow<Uuid?>

    /** Registers or refreshes this installation for the authenticated principal. */
    suspend fun register(): Uuid

    /** Updates the backend activity timestamp without requiring a principal association. */
    suspend fun checkIn()

    /** Disconnects the authenticated principal without changing device or push registration. */
    suspend fun disconnectPrincipal()
}
