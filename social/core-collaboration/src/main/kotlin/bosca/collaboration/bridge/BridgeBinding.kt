package bosca.collaboration.bridge

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Links a Bosca chat channel to an external messaging platform channel,
 * enabling bidirectional message synchronization.
 */
@Serializable
data class BridgeBinding(
    @Contextual
    val id: UUID,
    @ColumnName("channel_id")
    @Contextual
    val channelId: UUID,
    val platform: BridgePlatform,
    @ColumnName("external_channel_id")
    val externalChannelId: String,
    @ColumnName("workspace_id")
    val workspaceId: String,
    @ColumnName("webhook_url")
    val webhookUrl: String? = null,
    val active: Boolean = true,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime? = null,
)
