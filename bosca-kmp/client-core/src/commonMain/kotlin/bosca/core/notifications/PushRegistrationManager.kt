package bosca.core.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Public remote-push lifecycle consumed by KMP applications. */
interface PushRegistrationManager {
    /** Current provider-token registration state. */
    val state: StateFlow<PushRegistrationState>

    /** Remote messages delivered while the application process is active. */
    val messages: Flow<PushMessage>

    /** Whether this runtime has a native remote-push implementation. */
    val supported: Boolean

    /** Whether this runtime can request native notification presentation permission. */
    val permissionSupported: Boolean

    /** Whether foreground messages are already presented through native system UI. */
    val presentsForegroundNotifications: Boolean

    /** Starts provider-token observation and synchronizes the current token when a device is registered. */
    suspend fun initialize()

    /** Reconciles the current provider token with the registered device. */
    suspend fun synchronize()

    /** Requests native notification permission and reconciles the provider token when granted. */
    suspend fun requestPermission(): Boolean
}
