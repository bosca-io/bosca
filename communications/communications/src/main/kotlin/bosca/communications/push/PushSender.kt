package bosca.communications.push

import bosca.communications.model.PushOptions
import bosca.devices.model.PushProvider

/**
 * The device platform used to determine which push notification transport to use.
 */
enum class PushPlatform {
    ANDROID,
    WEB,
    IOS,
    DESKTOP,
}

/**
 * Tracks the outcome of a push notification delivery attempt, including
 * per-token success and failure counts for operational visibility.
 */
data class PushSendResult(
    /** The number of tokens that were successfully delivered to. */
    val successCount: Int = 0,
    /** The number of tokens that failed delivery. */
    val failureCount: Int = 0,
    /** Tokens the provider reported as permanently invalid or unregistered. */
    val invalidTokens: Set<String> = emptySet()
) {
    /** Returns true when every token in the batch failed delivery. */
    val allFailed: Boolean get() = successCount == 0 && failureCount > 0
}

/**
 * Abstracts the delivery of push notifications across different platforms.
 *
 * Implementations handle the platform-specific protocol details for delivering
 * notifications to devices via FCM, APNs, or other push services.
 */
interface PushSender {

    /**
     * Delivers a push notification to a list of device tokens on a specific platform.
     *
     * Routes to the appropriate push service (FCM, APNs) based on the [platform] parameter.
     * Returns a [PushSendResult] tracking per-token success and failure counts. Throws
     * on infrastructure-level failures (e.g., credentials misconfigured, service unavailable)
     * so callers can detect systemic delivery problems.
     *
     * @param tokens the device registration tokens to send the notification to
     * @param subject the notification title displayed to the user
     * @param content the notification body text
     * @param platform the target device platform determining the delivery transport
     * @param provider the service that issued the tokens and must deliver to them
     * @param options optional platform-specific push options controlling delivery, presentation, and payload
     * @return a [PushSendResult] with delivery counts and permanently invalid tokens
     */
    suspend fun send(
        tokens: List<String>,
        subject: String,
        content: String,
        platform: PushPlatform,
        provider: PushProvider,
        options: PushOptions? = null,
    ): PushSendResult
}
