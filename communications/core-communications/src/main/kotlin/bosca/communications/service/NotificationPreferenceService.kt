package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreference
import bosca.communications.model.NotificationSettings
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages per-user notification preferences across delivery
 * channels (email, push). Users can opt out of optional
 * notification types per channel; non-optional types (transactional,
 * security) always deliver regardless of preference.
 *
 * Preferences are stored sparsely. When a user has no explicit row,
 * the effective value comes from the notification type's configured
 * default. [getPreferences] materializes the full channel × type
 * matrix, so callers never need to reason about missing rows.
 */
interface NotificationPreferenceService : Service {

    /**
     * Get the full effective channel × type preference matrix for a
     * user, ordered by the type catalog. Combinations without a
     * stored row use the notification type's configured default;
     * stored rows for types no longer in the catalog are omitted.
     */
    suspend fun getPreferences(profileId: UUID): List<NotificationPreference>

    /**
     * Check whether a user has opted out of a notification type on
     * a channel. Non-optional and unknown types are never opted out
     * (unknown types fail open so a catalog edit can never silently
     * suppress delivery).
     */
    suspend fun isOptedOut(profileId: UUID, channel: DeliveryChannel, type: String): Boolean

    /**
     * Set opt-out status for a channel + type. Creates the
     * preference record if it doesn't exist. Throws
     * [IllegalArgumentException] when the type is unknown or when
     * attempting to opt out of a non-optional type.
     */
    suspend fun setOptOut(
        profileId: UUID,
        channel: DeliveryChannel,
        type: String,
        optedOut: Boolean,
    ): NotificationPreference

    /**
     * Get the user's notification settings (quiet hours). Returns
     * defaults (no quiet hours) when the user has never set any.
     */
    suspend fun getSettings(profileId: UUID): NotificationSettings

    /**
     * Set quiet hours for push delivery. All three of [timeZone]
     * (IANA zone id), [dndStartLocal], and [dndEndLocal] (HH:MM)
     * must be provided together, or all null to clear. Throws
     * [IllegalArgumentException] on malformed values.
     */
    suspend fun setQuietHours(
        profileId: UUID,
        timeZone: String?,
        dndStartLocal: String?,
        dndEndLocal: String?,
    ): NotificationSettings

    /**
     * Process a one-click unsubscribe via token. A type-scoped
     * token opts out that type on the email channel; an unscoped
     * token opts out all optional types on the email channel.
     * Returns true if the token was valid.
     */
    suspend fun unsubscribeByToken(token: String): Boolean

    /**
     * Resolve the profile a token belongs to, for rendering the
     * manage-preferences page without login. Returns null for an
     * unknown token.
     */
    suspend fun profileIdForToken(token: String): UUID?

    /**
     * Generate an unsubscribe token for a profile, optionally
     * scoped to a notification type for one-click type unsubscribe.
     */
    suspend fun generateUnsubscribeToken(profileId: UUID, type: String?): String
}
