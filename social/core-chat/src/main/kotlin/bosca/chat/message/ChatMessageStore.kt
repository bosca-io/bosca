package bosca.chat.message

import bosca.chat.model.ChatMessage
import bosca.chat.model.ChatMessageSendResult
import bosca.communications.model.MessageContent
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/** Message persistence and live delivery; membership and authorization remain in the chat service. */
interface ChatMessageStore {
    /** Streams newly stored messages and deletion events for the channel. */
    fun subscribe(channelId: UUID): Flow<ChatMessage>
    /** Stores a message with scoped client-ID deduplication and returns its durable sequence. */
    suspend fun sendMessage(channelId: UUID, senderId: UUID, clientId: UUID, content: List<MessageContent>, attributes: JsonElement?, parentSequence: Long?): ChatMessageSendResult
    /** Reads channel history in ascending sequence order within the existing before/after bounds. */
    suspend fun getMessages(channelId: UUID, before: Long?, after: Long?, limit: Int): List<ChatMessage>
    /** Reads a message only when it belongs to the requested channel. */
    suspend fun getMessage(channelId: UUID, sequence: Long): ChatMessage?
    /** Deletes a stored message and publishes its tombstone. */
    suspend fun deleteMessage(channelId: UUID, sequence: Long): Boolean
}
