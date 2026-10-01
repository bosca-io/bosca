package bosca.ai.chat.model

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessagePart(
    val type: String,
    val text: String
)

@Serializable
data class ChatMessagePartInput(
    val type: String,
    val text: String
)

@Serializable
data class ChatMessageInput(
    val role: String,
    val parts: List<ChatMessagePartInput>
)

@Serializable
data class ChatMessage(
    val id: String,
    val role: String,
    val parts: List<ChatMessagePart>
) {

    fun toMessage(): Message {
        return when (role) {
            "user" -> Message.User(parts.map { MessagePart.Text(it.text) }, RequestMetaInfo.Empty)
            "assistant" -> Message.Assistant(parts.map { MessagePart.Text(it.text) }, ResponseMetaInfo.Empty, null)
            else -> error("Unknown role: $role")
        }
    }
}

@Serializable
data class ChatRequest(
    val id: String,
    val messages: List<ChatMessage>,
    val trigger: String,
    val sessionId: String? = null
)

@Serializable
data class MessageStart(
    val type: String = "start",
    val messageId: String
)

@Serializable
data class MessageFinish(
    val type: String = "finish"
)

@Serializable
data class ReasoningStart(
    val type: String = "reasoning-start",
    val id: String
)

@Serializable
data class ReasoningDelta(
    val type: String = "reasoning-delta",
    val id: String,
    val delta: String
)

@Serializable
data class ReasoningEnd(
    val type: String = "reasoning-end",
    val id: String
)

@Serializable
data class TextStart(
    val type: String = "text-start",
    val id: String
)

@Serializable
data class TextDelta(
    val type: String = "text-delta",
    val id: String,
    val delta: String
)

@Serializable
data class TextEnd(
    val type: String = "text-end",
    val id: String
)