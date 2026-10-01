package bosca.collaboration.bridge

import bosca.chat.model.ChatMessage

/**
 * Platform-specific adapter for sending and managing messages on an external
 * messaging platform. Each platform (Slack, Teams) implements this interface
 * with its own API calls and webhook verification.
 */
interface BridgePlatformAdapter {

    /** The platform this adapter handles */
    val platform: BridgePlatform

    /**
     * Posts a message to the external platform channel.
     * @return the external platform's message ID for tracking
     */
    suspend fun sendMessage(binding: BridgeBinding, message: ChatMessage, senderName: String): String

    /** Updates an existing message on the external platform */
    suspend fun editMessage(binding: BridgeBinding, externalMessageId: String, message: ChatMessage, senderName: String)

    /** Deletes a message from the external platform */
    suspend fun deleteMessage(binding: BridgeBinding, externalMessageId: String)

    /** Adds an emoji reaction to a message on the external platform */
    suspend fun addReaction(binding: BridgeBinding, externalMessageId: String, emoji: String)

    /** Removes an emoji reaction from a message on the external platform */
    suspend fun removeReaction(binding: BridgeBinding, externalMessageId: String, emoji: String)

    /** Verifies the webhook signature from the external platform */
    fun verifyWebhookSignature(headers: Map<String, String>, body: ByteArray, signingSecret: String): Boolean
}
