package bosca.communications.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Serializable

/**
 * Routes one Bosca notification preference cell to an external preference.
 *
 * Mappings are channel-scoped because an external provider may own the email preference for a
 * notification type while Bosca continues to own that type's push preference.
 */
@Serializable
data class NotificationPreferenceMapping(
    val type: String,
    val channel: DeliveryChannel,
    val provider: String,
    @ColumnName("external_id")
    val externalId: String,
    val created: OffsetDateTime = OffsetDateTime.now(),
    val modified: OffsetDateTime = OffsetDateTime.now(),
)
