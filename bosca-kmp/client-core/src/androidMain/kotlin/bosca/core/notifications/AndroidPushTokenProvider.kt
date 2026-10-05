package bosca.core.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Activity contract used by client-core to request Android 13+ notification permission. */
interface AndroidNotificationPermissionHost {
    /** Launches the host Activity's notification permission request and returns its result. */
    suspend fun requestNotificationPermission(): Boolean
}

/** Firebase Cloud Messaging token source for Android applications. */
class AndroidPushTokenProvider(context: Context) : PushTokenProvider {
    private val context = context
    private val firebaseApp = runCatching {
        FirebaseApp.getInstance()
    }.recoverCatching {
        FirebaseApp.initializeApp(context) ?: error("Firebase is not configured")
    }.getOrNull()

    override val platform: PushDevicePlatform = PushDevicePlatform.ANDROID
    override val supported: Boolean = firebaseApp != null
    override val permissionSupported: Boolean = true
    override val presentsForegroundNotifications: Boolean = true
    override val tokenChanges: Flow<String> = AndroidPushEvents.tokenChanges.asSharedFlow()
    override val messages: Flow<PushMessage> = AndroidPushEvents.messages.asSharedFlow()

    override suspend fun currentToken(): String? {
        if (firebaseApp == null) return null
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                val error = task.exception
                if (task.isSuccessful && error == null) continuation.resume(task.result)
                else continuation.resumeWithException(error ?: IllegalStateException("FCM token request failed"))
            }
        }
    }

    override suspend fun deleteToken() {
        if (firebaseApp == null) return
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                val error = task.exception
                if (task.isSuccessful && error == null) continuation.resume(Unit)
                else continuation.resumeWithException(error ?: IllegalStateException("FCM token deletion failed"))
            }
        }
    }

    override suspend fun requestPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return (context as? AndroidNotificationPermissionHost)?.requestNotificationPermission() == true
    }

    override suspend fun synchronizeNotificationTypes(types: List<NotificationTypeDefinition>) {
        AndroidSystemNotifications.synchronizeChannels(context, types)
    }
}
