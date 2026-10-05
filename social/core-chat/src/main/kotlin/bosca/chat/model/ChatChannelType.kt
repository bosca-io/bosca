package bosca.chat.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Defines the participant model and visibility scope for a chat channel.
 */
@DbMapper(ChatChannelTypeMapper::class)
@Serializable
enum class ChatChannelType {
    /** A private one-on-one conversation between two users */
    DIRECT,
    /** A group conversation visible only to its members */
    GROUP,
    /** A channel open to all eligible members */
    PUBLIC
}

object ChatChannelTypeMapper : EnumMapper<ChatChannelType>({ ChatChannelType.valueOf(it.uppercase()) })
