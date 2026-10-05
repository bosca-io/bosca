package bosca.communications.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Serializable

/**
 * An admin-definable notification type — the unit users opt in or
 * out of, per delivery channel. [key] is the immutable contract
 * that sending code references; [name] and [description] are
 * presentation and freely editable.
 *
 * Types with [optional] = false always deliver and cannot be opted
 * out. For optional types, [defaultEmailEnabled] and [defaultPushEnabled]
 * control the effective preference until a user explicitly changes the
 * corresponding delivery channel.
 * [system] rows are seeded by migration, cannot be deleted, and their
 * [optional] flag cannot be changed. [hidden] types remain available
 * to sending code and administrators but are omitted from user
 * preference controls.
 */
@Serializable
data class NotificationType(
    val key: String,
    val name: String,
    val description: String? = null,
    val optional: Boolean = true,
    val system: Boolean = false,
    @ColumnName("default_email_enabled")
    val defaultEmailEnabled: Boolean = true,
    @ColumnName("default_push_enabled")
    val defaultPushEnabled: Boolean = true,
    @ColumnName("display_order")
    val displayOrder: Int = 0,
    @ColumnName("created_at")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("updated_at")
    val updatedAt: OffsetDateTime = OffsetDateTime.now(),
    val hidden: Boolean = false,
) {
    /** Returns the configured default for one delivery channel. */
    fun defaultEnabled(channel: DeliveryChannel): Boolean = when (channel) {
        DeliveryChannel.EMAIL -> defaultEmailEnabled
        DeliveryChannel.PUSH -> defaultPushEnabled
    }
}
