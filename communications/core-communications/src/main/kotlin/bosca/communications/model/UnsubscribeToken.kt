package bosca.communications.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * A token embedded in outbound email that authenticates a profile
 * without login. When [type] is set the token one-click
 * unsubscribes that notification type; when null it opts out all
 * optional types and authenticates the manage-preferences page.
 */
@Serializable
data class UnsubscribeToken(
    val token: String,
    @ColumnName("profile_id")
    val profileId: UUID,
    val type: String? = null,
    @ColumnName("created_at")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
