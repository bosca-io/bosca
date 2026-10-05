package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.serialization.UUID

/**
 * An external system that owns mapped notification preference values.
 *
 * Implementations read and write the external system on demand. Bosca remains authoritative for
 * preference cells that have no external mapping.
 */
interface ExternalNotificationPreferenceProvider {

    /** Stable provider key stored in notification preference mappings. */
    val key: String

    /**
     * Read opt-out values for [externalIds] in one provider call.
     *
     * Missing IDs have no explicit external state, so the caller
     * applies the notification type's configured default.
     */
    suspend fun getOptOuts(
        profileId: UUID,
        channel: DeliveryChannel,
        externalIds: Set<String>,
    ): Map<String, Boolean>

    /** Write one mapped opt-out value to the external provider. */
    suspend fun setOptOut(
        profileId: UUID,
        channel: DeliveryChannel,
        externalId: String,
        optedOut: Boolean,
    )
}
