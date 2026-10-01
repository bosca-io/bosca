package bosca.core.notifications

import kotlinx.coroutines.flow.MutableStateFlow

/** Decides whether a received push message should be shown using native system UI. */
fun interface PushNotificationPresentationPolicy {
    /** Returns true to present [message], or false to deliver it without native presentation. */
    fun shouldPresent(message: PushMessage): Boolean
}

/** Process-wide policy used by platform hosts before presenting received push notifications. */
object PushNotificationPresentation {
    private val policy = MutableStateFlow<PushNotificationPresentationPolicy?>(null)

    /**
     * Installs [presentationPolicy], replacing any previously installed policy.
     *
     * Passing null restores the default behavior, which presents every received message. Policies
     * can be called from a platform push callback thread and should return without blocking.
     */
    fun setPolicy(presentationPolicy: PushNotificationPresentationPolicy?) {
        policy.value = presentationPolicy
    }

    /**
     * Returns whether [message] should be presented using native system UI.
     *
     * Android's `BoscaFirebaseMessagingService` applies this automatically. Apple notification
     * delegates should call this before choosing foreground presentation options. A policy failure
     * falls back to presentation so an application callback cannot silently discard notifications.
     */
    fun shouldPresent(message: PushMessage): Boolean =
        policy.value?.let { presentationPolicy ->
            runCatching { presentationPolicy.shouldPresent(message) }.getOrDefault(true)
        } ?: true
}
