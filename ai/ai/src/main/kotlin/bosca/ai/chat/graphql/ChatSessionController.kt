package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatHistoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@TypeController
class ChatSessionController(
    private val service: ChatHistoryService
) : GraphQLController<ChatSession> {

    @Field
    fun id(session: ChatSession): Uuid = session.id

    @Field
    fun principalId(session: ChatSession): Uuid = session.principalId

    @Field
    fun parentSessionId(session: ChatSession): Uuid? = session.parentSessionId

    @Field
    fun agentKey(session: ChatSession): String = session.agentKey

    @Field
    fun title(session: ChatSession): String = session.title

    @Field
    fun attachments(session: ChatSession): List<Uuid> {
        return parseUuidArray(session, "attachments")
    }

    @Field
    fun collectionAttachments(session: ChatSession): List<Uuid> {
        return parseUuidArray(session, "collectionAttachments")
    }

    private fun parseUuidArray(session: ChatSession, key: String): List<Uuid> {
        val state = session.state as? JsonObject ?: return emptyList()
        val arr = state[key] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { element ->
            (element as? JsonPrimitive)?.content?.let { Uuid.parse(it) }
        }
    }

    @Field
    fun status(session: ChatSession) = session.status

    @Field
    fun processing(session: ChatSession): Boolean = session.processing

    @Field
    fun created(session: ChatSession): OffsetDateTime = session.created

    @Field
    fun modified(session: ChatSession): OffsetDateTime = session.modified

    @Field
    suspend fun messages(session: ChatSession): List<ChatHistoryMessage> {
        return service.getMessages(session.id)
    }
}
