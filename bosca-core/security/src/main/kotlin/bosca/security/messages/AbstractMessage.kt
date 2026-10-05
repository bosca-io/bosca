package bosca.security.messages

import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.service.MessageService
import bosca.serialization.UUID

interface EmailTemplate {
    suspend fun initialize(profileId: UUID)
    suspend fun getSubject(): String
    suspend fun getHtml(): String
    suspend fun getText(): String
}

abstract class AbstractMessage<T : EmailTemplate>(
    private val messages: MessageService,
    private val templates: T
) {

    suspend fun send(profileId: UUID) {
        templates.initialize(profileId)
        val html = templates.getHtml()
        val text = templates.getText()
        val message = Message(
            subject = templates.getSubject(),
            channels = listOf(MessageChannel.EMAIL),
            recipients = listOf(profileId),
            content = listOf(
                MessageContent(
                    type = MessageContentType.HTML,
                    content = html
                ),
                MessageContent(
                    type = MessageContentType.TEXT,
                    content = text
                )
            ),
        )
        messages.send(message)
    }
}