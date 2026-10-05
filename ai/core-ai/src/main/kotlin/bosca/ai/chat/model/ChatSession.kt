@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.chat.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class ChatSession(
    @Contextual
    val id: Uuid = Uuid.NIL,
    @Contextual
    @ColumnName("principal_id")
    val principalId: Uuid,
    /** The chat session this one hangs off of, if any — e.g. a sub-thread spawned from another session. */
    @Contextual
    @ColumnName("parent_session_id")
    val parentSessionId: Uuid? = null,
    @ColumnName("agent_key")
    val agentKey: String,
    val title: String = "",
    val status: ChatSessionStatus = ChatSessionStatus.COMPLETED,
    val processing: Boolean = false,
    @Contextual
    val state: JsonElement = JsonObject(emptyMap()),
    val created: OffsetDateTime = java.time.OffsetDateTime.now(),
    val modified: OffsetDateTime = java.time.OffsetDateTime.now()
)
