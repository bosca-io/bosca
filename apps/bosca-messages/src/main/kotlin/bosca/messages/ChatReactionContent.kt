package bosca.messages

import bosca.bml.graphql.execute
import bosca.bml.message.BmlMessageContext
import bosca.messages.graphql.ChatNotificationReaction

/** Render-time reaction content that is deliberately excluded from the persisted notification payload. */
data class ChatReactionContent(
    val emoji: String,
)

/** Retrieves the referenced reaction through the message renderer's authenticated GraphQL client. */
suspend fun chatReactionContent(
    context: BmlMessageContext,
    notification: ChatReactionNotification,
): ChatReactionContent {
    val gql = requireNotNull(context.gql) {
        "chat-reaction requires the BML message server's authenticated GraphQL client"
    }
    val messages = gql.execute(
        ChatNotificationReaction,
        ChatNotificationReaction.Variables(
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
    val reaction = requireNotNull(source.reactions.singleOrNull { it.id == notification.reactionId }) {
        "chat reaction ${notification.reactionId} was not found on ${notification.channelId}/${notification.sequence}"
    }
    return ChatReactionContent(reaction.emoji)
}
