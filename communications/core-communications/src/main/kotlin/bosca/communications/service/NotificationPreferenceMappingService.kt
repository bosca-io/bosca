package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreferenceMapping
import bosca.service.Service

/**
 * Manages channel-scoped mappings from Bosca notification types to external preferences.
 */
interface NotificationPreferenceMappingService : Service {

    /** List every configured mapping in stable type/channel order. */
    suspend fun list(): List<NotificationPreferenceMapping>

    /** Return the mapping for one preference cell, or null when Bosca owns it. */
    suspend fun get(type: String, channel: DeliveryChannel): NotificationPreferenceMapping?

    /**
     * Create or replace a mapping.
     *
     * The notification type must exist and be optional. Any local rows for the mapped cell are
     * removed so a later read cannot accidentally use stale local state.
     */
    suspend fun set(
        type: String,
        channel: DeliveryChannel,
        provider: String,
        externalId: String,
    ): NotificationPreferenceMapping

    /** Delete a mapping, returning false when no mapping existed. */
    suspend fun delete(type: String, channel: DeliveryChannel): Boolean
}
