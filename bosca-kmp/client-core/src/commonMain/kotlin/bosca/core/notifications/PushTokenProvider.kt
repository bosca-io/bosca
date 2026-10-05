package bosca.core.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Platform boundary for obtaining and rotating a remote-push provider token.
 *
 * Platform implementations own notification permission and native channel synchronization.
 * Android hosts only need to implement the Activity-owned permission result contract exposed
 * by client-core.
 */
interface PushTokenProvider {
    /** Platform stored with the backend device record. */
    val platform: PushDevicePlatform

    /** Service that issued the token. Defaults to FCM for existing implementations. */
    val transport: PushTokenTransport get() = PushTokenTransport.FCM

    /** Whether this runtime can receive remote push notifications. */
    val supported: Boolean

    /** Whether this runtime can request permission to present remote notifications. */
    val permissionSupported: Boolean get() = supported

    /** Whether foreground pushes are already presented through native system notification UI. */
    val presentsForegroundNotifications: Boolean get() = false

    /** Provider-issued token rotations observed while the process is alive. */
    val tokenChanges: Flow<String>

    /** Native registration failures that can be retried by requesting a token again. */
    val registrationFailures: Flow<String> get() = emptyFlow()

    /** Remote messages delivered to the running application. */
    val messages: Flow<PushMessage>

    /** Returns the current provider token, or null until native registration completes. */
    suspend fun currentToken(): String?

    /** Invalidates the provider token for this installation when supported. */
    suspend fun deleteToken()

    /** Requests native notification presentation permission. */
    suspend fun requestPermission(): Boolean = supported

    /** Reconciles native presentation channels with the current server notification types. */
    suspend fun synchronizeNotificationTypes(types: List<NotificationTypeDefinition>) = Unit
}

/** Token provider used by platforms without remote-push support. */
class UnsupportedPushTokenProvider(
    override val platform: PushDevicePlatform,
) : PushTokenProvider {
    override val supported: Boolean = false
    override val permissionSupported: Boolean = false
    override val tokenChanges: Flow<String> = emptyFlow()
    override val messages: Flow<PushMessage> = emptyFlow()
    override suspend fun currentToken(): String? = null
    override suspend fun deleteToken() = Unit
    override suspend fun requestPermission(): Boolean = false
}
