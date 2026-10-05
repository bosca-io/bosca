package bosca.messages

import bosca.bml.graphql.execute
import bosca.bml.message.BmlMessageContext
import bosca.messages.graphql.ChatNotificationMessage
import bosca.messages.graphql.MessageContentType

/** Render-time chat content that is deliberately excluded from the persisted notification payload. */
data class ChatMessageContent(
    val messageText: String?,
    val attachmentCount: Int,
)

/**
 * Retrieves the exact source message through the message renderer's authenticated GraphQL client.
 * Only [ChatMessageNotification.channelId] and [ChatMessageNotification.sequence] remain in delivery
 * audit parameters; user-authored content exists here only for the duration of the render.
 */
suspend fun chatMessageContent(
    context: BmlMessageContext,
    notification: ChatMessageNotification,
): ChatMessageContent {
    val gql = requireNotNull(context.gql) {
        "chat-message requires the BML message server's authenticated GraphQL client"
    }
    val messages = gql.execute(
        ChatNotificationMessage,
        ChatNotificationMessage.Variables(
            channelId = notification.channelId,
            after = (notification.sequence - 1).coerceAtLeast(0),
        ),
    ).chat.channel?.messages.orEmpty()
    val source = requireNotNull(messages.singleOrNull { it.sequence == notification.sequence }) {
        "chat message ${notification.channelId}/${notification.sequence} was not found"
    }
    require(!source.deleted) {
        "chat message ${notification.channelId}/${notification.sequence} was deleted"
    }
    return ChatMessageContent(
        messageText = source.content
            .firstOrNull { it.type == MessageContentType.TEXT }
            ?.content
            ?.takeIf { it.isNotBlank() },
        attachmentCount = source.content.count {
            when (it.type) {
                MessageContentType.TEXT,
                MessageContentType.HTML,
                MessageContentType.MENTION -> false
                else -> true
            }
        },
    )
}
