package bosca.ai.kit.agents.session

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.KSerializerTypeToken
import ai.koog.serialization.annotations.InternalKoogSerializationApi
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.configuration.KitJson
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlin.uuid.Uuid

/**
 * Stores Kit's canonical conversation turns through [ChatHistoryService] as Koog [Message]s serialized
 * with the Kit Json, so every conversational row shares one shape.
 */
@ServiceImplementation
@OptIn(InternalKoogSerializationApi::class)
class KitSessionServiceImpl(
    private val chatHistory: ChatHistoryService,
    json: KitJson,
) : KitSessionService {

    private val serializer = KitSerializer(KotlinxSerializer(json.json))

    override suspend fun recordUserMessage(sessionId: UUID, message: ChatMessageInput) {
        val text = message.parts.joinToString("\n") { it.text }
        chatHistory.addMessage(historyMessage(sessionId, Message.User(text, RequestMetaInfo.Empty)))
    }

    override suspend fun recordResponse(sessionId: UUID, response: KitResponse) {
        val text = when (response) {
            is KitResponse.Text -> response.text
        }
        chatHistory.addMessage(historyMessage(sessionId, Message.Assistant(text, ResponseMetaInfo.Empty)))
    }

    /** A conversational row carrying a Koog [message], serialized with the Kit Json. */
    private fun historyMessage(sessionId: Uuid, message: Message) = ChatHistoryMessage(
        id = Uuid.random(),
        sessionId = sessionId,
        author = message.role.name,
        event = serializer.encodeToJSONElement(message, KSerializerTypeToken(Message.serializer())).toKotlinxJsonElement(),
    )
}
