package bosca.ai.chat.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatHistoryMessage(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("session_id")
    val sessionId: UUID = UUID.NIL,
    val author: String,
    @Contextual
    val content: JsonElement? = null,
    @ColumnName("event_data")
    @Contextual
    val event: JsonElement,
    val created: OffsetDateTime = java.time.OffsetDateTime.now()
)
