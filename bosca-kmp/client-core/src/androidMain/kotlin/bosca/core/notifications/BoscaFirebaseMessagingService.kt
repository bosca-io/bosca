package bosca.core.notifications

import bosca.core.preferences.auth.AndroidTokenStorage
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

/** Reserved [PushMessage.data] key containing FCM's requested Android channel ID. */
const val ANDROID_NOTIFICATION_CHANNEL_ID_KEY = "bosca_android_channel_id"

/** Reserved [PushMessage.data] key containing FCM's Android notification replacement tag. */
const val ANDROID_NOTIFICATION_TAG_KEY = "bosca_android_notification_tag"

/** Reserved [PushMessage.data] key containing the requested pre-Android-8 notification sound. */
const val ANDROID_NOTIFICATION_SOUND_KEY = "bosca_android_sound"

/** Reserved [PushMessage.data] key containing FCM's notification link. */
const val ANDROID_NOTIFICATION_LINK_KEY = "bosca_android_notification_link"

internal object AndroidPushEvents {
    val tokenChanges = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = MutableSharedFlow<PushMessage>(extraBufferCapacity = 16)
}

/** Receives FCM token rotations and presents incoming messages using client-core's system UI. */
class BoscaFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AndroidPushEvents.tokenChanges.tryEmit(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val providerNotification = message.notification
        val pushMessage = PushMessage(
            id = message.messageId,
            title = providerNotification?.title ?: message.data["title"],
            body = providerNotification?.body ?: message.data["body"],
            data = buildMap {
                putAll(message.data)
                providerNotification?.channelId?.let { putIfAbsent(ANDROID_NOTIFICATION_CHANNEL_ID_KEY, it) }
                providerNotification?.tag?.let { putIfAbsent(ANDROID_NOTIFICATION_TAG_KEY, it) }
                providerNotification?.link?.toString()?.let {
                    putIfAbsent(ANDROID_NOTIFICATION_LINK_KEY, it)
                }
            },
        )
        AndroidPushEvents.messages.tryEmit(pushMessage)
        if (!PushNotificationPresentation.shouldPresent(pushMessage)) return
        val accessToken = runCatching {
            runBlocking { AndroidTokenStorage(applicationContext).getToken() }
        }.getOrNull()
        AndroidSystemNotifications.show(this, pushMessage, accessToken)
    }
}
