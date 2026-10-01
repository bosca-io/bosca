package bosca.ai.chat.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(ChatSessionStatusMapper::class)
@Serializable
enum class ChatSessionStatus {
    STREAMING,
    COMPLETED,
    FAILED
}

object ChatSessionStatusMapper : EnumMapper<ChatSessionStatus>({ ChatSessionStatus.valueOf(it.uppercase()) })
