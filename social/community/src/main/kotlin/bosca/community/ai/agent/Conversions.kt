package bosca.community.ai.agent

import ai.koog.prompt.message.MessagePart.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import bosca.community.model.ChatMessage
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.serialization.UUID

fun MessageContent.toContentPart(): ContentPart {
    return when (type) {
        MessageContentType.TEXT -> MessagePart.Text(content)
        else -> MessagePart.Text(content)
    }
}

fun ChatMessage.toMessage(agentProfileId: UUID): Message {
    val parts = content.map { it.toContentPart() }
    return if (senderId == agentProfileId) {
        Message.Assistant(parts, ResponseMetaInfo.Empty, null)
    } else {
        Message.User(parts, RequestMetaInfo.Empty)
    }
}
