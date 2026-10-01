package bosca.core.notifications

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIApplication
import platform.UIKit.registerForRemoteNotifications
import platform.UIKit.unregisterForRemoteNotifications
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

private object IosPushEvents {
    var currentToken: String? = null
    val tokenChanges = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val registrationFailure = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<PushMessage>(extraBufferCapacity = 16)
}

/** Direct Apple Push Notification service token source for iOS applications. */
@OptIn(ExperimentalForeignApi::class)
class IosPushTokenProvider : PushTokenProvider {
    override val platform: PushDevicePlatform = PushDevicePlatform.IOS
    override val transport: PushTokenTransport = PushTokenTransport.APNS
    override val supported: Boolean = true
    override val permissionSupported: Boolean = true
    override val presentsForegroundNotifications: Boolean = true
    override val tokenChanges: Flow<String> = IosPushEvents.tokenChanges.asSharedFlow()
    override val registrationFailures: Flow<String> = IosPushEvents.registrationFailure.filterNotNull()
    override val messages: Flow<PushMessage> = IosPushEvents.messages.asSharedFlow()

    override suspend fun currentToken(): String? {
        IosPushEvents.registrationFailure.value = null
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.registerForRemoteNotifications()
        }
        return IosPushEvents.currentToken
    }

    override suspend fun deleteToken() {
        IosPushEvents.currentToken = null
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.unregisterForRemoteNotifications()
        }
    }

    override suspend fun requestPermission(): Boolean =
        IosPushNotifications.requestAuthorizationAndRegister()
}

/**
 * Bridge called by the host iOS application's notification delegate.
 *
 * Pass the lower-case hexadecimal APNs device token from
 * `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`, and forward foreground or
 * tapped payloads through [didReceiveRemoteNotification].
 */
@OptIn(ExperimentalForeignApi::class)
object IosPushNotifications {
    /** Requests alert/sound/badge permission and starts APNs registration when granted. */
    suspend fun requestAuthorizationAndRegister(): Boolean = suspendCancellableCoroutine { continuation ->
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(options) { granted, _ ->
                if (granted) {
                    IosPushEvents.registrationFailure.value = null
                    dispatch_async(dispatch_get_main_queue()) {
                        UIApplication.sharedApplication.registerForRemoteNotifications()
                    }
                }
                if (continuation.isActive) continuation.resume(granted)
            }
    }

    /** Supplies the APNs token received by the host application delegate. */
    fun didRegisterForRemoteNotifications(token: String) {
        val normalized = token.trim().lowercase()
        if (normalized.isEmpty()) return
        IosPushEvents.registrationFailure.value = null
        IosPushEvents.currentToken = normalized
        IosPushEvents.tokenChanges.tryEmit(normalized)
    }

    /** Supplies an APNs registration failure received by the host application delegate. */
    fun didFailToRegisterForRemoteNotifications(message: String) {
        IosPushEvents.currentToken = null
        IosPushEvents.registrationFailure.value = message.takeIf { it.isNotBlank() }
            ?: "APNs registration failed"
    }

    /** Supplies a remote notification received or opened by the host application delegate. */
    fun didReceiveRemoteNotification(
        id: String?,
        title: String?,
        body: String?,
        data: Map<String, String> = emptyMap(),
    ) {
        IosPushEvents.messages.tryEmit(PushMessage(id, title, body, data))
    }
}
