package bosca.communications.push

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Top-level configuration for push notification delivery.
 *
 * When [enabled] is false, all push send operations are silently skipped.
 * The [fcm] and [apns] sub-configurations control which platforms are supported
 * and how credentials are provided for each push service.
 */
@Serializable
data class PushConfiguration(
    val enabled: Boolean = false,
    val fcm: FcmConfiguration? = null,
    val apns: ApnsConfiguration? = null,
) {
    companion object {
        /** Configuration-service key for push delivery settings and credentials. */
        const val KEY = "push"
    }
}

/**
 * Firebase Cloud Messaging configuration for Android and web push delivery.
 *
 * @param serviceAccountJson the Google service account JSON key used to authenticate
 *   with the FCM API; if null, Application Default Credentials are used
 */
@Serializable
data class FcmConfiguration(
    val serviceAccountJson: String? = null,
)

/**
 * Apple Push Notification service configuration for direct iOS push delivery.
 *
 * When complete APNs configuration is provided, APNs-issued iOS tokens are sent directly
 * to Apple's servers using token-based authentication (JWT). Legacy platform-only calls may
 * still fall back to FCM when APNs is not configured.
 *
 * @param teamId the Apple Developer Team ID
 * @param keyId the APNs authentication key identifier
 * @param bundleId the iOS app bundle identifier used as the APNs topic
 * @param privateKey the PEM-encoded P-256 private key for JWT signing
 * @param sandbox whether to use the APNs sandbox environment for development builds
 */
@Serializable
data class ApnsConfiguration(
    val teamId: String? = null,
    val keyId: String? = null,
    val bundleId: String? = null,
    val privateKey: String? = null,
    val sandbox: Boolean = true,
) {
    /** Whether every credential required for direct APNs delivery is present. */
    val isConfigured: Boolean
        get() = !teamId.isNullOrBlank() &&
            !keyId.isNullOrBlank() &&
            !bundleId.isNullOrBlank() &&
            !privateKey.isNullOrBlank()
}

/** Resolves the current push settings from encrypted platform configuration storage. */
internal suspend fun ConfigurationService.getPushConfiguration(json: Json): PushConfiguration =
    getValueAs<PushConfiguration>(PushConfiguration.KEY, json) ?: PushConfiguration()
