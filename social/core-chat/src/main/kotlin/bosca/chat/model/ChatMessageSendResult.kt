package bosca.chat.model

/**
 * Result of publishing a chat message.
 *
 * [duplicate] is true when the client-generated id already identified a message from the sender in
 * the target channel. In that case [sequence] is the original message's sequence and no
 * message-sent effects are dispatched again.
 *
 * @property sequence the sequence assigned to the original stored message
 * @property duplicate whether the client id already identified a message from this sender in the channel
 */
data class ChatMessageSendResult(
    val sequence: Long,
    val duplicate: Boolean,
)
