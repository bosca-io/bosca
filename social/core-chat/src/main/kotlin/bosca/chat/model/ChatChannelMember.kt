package bosca.chat.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Links a user profile to a chat channel along with their role.
 *
 * Last-read tracking lives in the `chat-read-state` NATS KV bucket
 * (see [bosca.chat.state.ChatReadStateStore]) rather than on the row,
 * so that ephemeral cursor state moves at NATS speed without churning
 * the relational membership row. Resolve a member's last-read position
 * via [bosca.chat.service.ChatService.getLastRead].
 *
 * The legacy `last_read_at` and `last_read_sequence` columns still
 * exist on `chat.channel_members` from the v0 schema migration; they
 * are unused and will be cleaned up by a future migration. Don't
 * project them into this model — readers should always go through
 * the KV store so a single source of truth is honoured.
 */
@Serializable
data class ChatChannelMember(
    @ColumnName("channel_id")
    @Contextual
    val channelId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    val role: String,
    val attributes: JsonElement? = null,
)
