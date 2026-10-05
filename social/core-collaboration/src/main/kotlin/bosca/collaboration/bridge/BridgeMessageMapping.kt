package bosca.collaboration.bridge

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Maps a Bosca message (channel + sequence) to its external platform
 * message identifier, enabling edit/delete synchronization.
 */
@Serializable
data class BridgeMessageMapping(
    @Contextual
    val id: UUID,
    @ColumnName("channel_id")
    @Contextual
    val channelId: UUID,
    val sequence: Long,
    val platform: BridgePlatform,
    @ColumnName("external_message_id")
    val externalMessageId: String,
)
