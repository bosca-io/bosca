package bosca.core.notifications

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Device-owned, bounded history used to update Android's native MessagingStyle notification. */
internal object ConversationNotificationStore {
    private const val PREFERENCES = "bosca.notification.conversations"
    private const val MAX_MESSAGES = 25
    private val json = Json { ignoreUnknownKeys = true }

    fun append(
        context: Context,
        conversation: PushConversation,
        attachmentUri: String?,
        attachmentMediaType: String?,
    ): List<StoredConversationMessage> {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = conversation.id.sha256()
        val prior = preferences.getString(key, null)?.let { encoded ->
            runCatching {
                json.decodeFromString(StoredConversation.serializer(), encoded)
            }.getOrNull()
        } ?: StoredConversation()
        val current = StoredConversationMessage(
            id = conversation.messageId,
            senderId = conversation.senderId,
            senderName = conversation.senderName,
            body = conversation.body,
            sentAtEpochMilliseconds = conversation.sentAtEpochMilliseconds
                ?: System.currentTimeMillis(),
            attachmentUri = attachmentUri,
            attachmentMediaType = attachmentMediaType,
        )
        val messages = (prior.messages.filterNot { it.id == current.id } + current)
            .sortedBy { it.sentAtEpochMilliseconds }
            .takeLast(MAX_MESSAGES)
        preferences.edit().putString(
            key,
            json.encodeToString(StoredConversation.serializer(), StoredConversation(messages)),
        ).apply()
        return messages
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

@Serializable
private data class StoredConversation(
    val messages: List<StoredConversationMessage> = emptyList(),
)

@Serializable
internal data class StoredConversationMessage(
    val id: String,
    val senderId: String? = null,
    val senderName: String? = null,
    val body: String,
    val sentAtEpochMilliseconds: Long,
    val attachmentUri: String? = null,
    val attachmentMediaType: String? = null,
)
